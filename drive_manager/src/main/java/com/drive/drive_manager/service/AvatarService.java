package com.drive.drive_manager.service;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;

/**
 * Players' icons: an image they upload, cut to a square and scaled to {@value #SIZE} px, kept in R2 under
 * {@code avatars/<userId>.jpg}. Which users have one (and its version, for the URL) is in {@code user_avatars}.
 * When a user is deleted (UsersService, same database) the cleanup removes their icon.
 */
@Service
public class AvatarService {

    private static final Logger log = LoggerFactory.getLogger(AvatarService.class);
    static final int SIZE = 256;
    /** The largest file accepted, before scaling. */
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final String COLLECTION = "user_avatars";

    private final R2Service r2;
    private final MongoTemplate mongo;

    @Value("${r2.public-url:}")
    private String publicUrl;

    public AvatarService(R2Service r2, MongoTemplate mongo) {
        this.r2 = r2;
        this.mongo = mongo;
    }

    private static String key(String userId) {
        return "avatars/" + userId + ".jpg";
    }

    /** Stores the user's icon from the uploaded image and returns its URL. */
    public String put(String userId, byte[] image) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(image));
        if (source == null) throw new IllegalArgumentException("El archivo no es una imagen JPG, PNG, GIF o BMP");
        byte[] jpeg = square(source);
        r2.uploadRaw(key(userId), new ByteArrayInputStream(jpeg), jpeg.length);
        long version = Instant.now().getEpochSecond();
        mongo.upsert(Query.query(Criteria.where("_id").is(userId)), Update.update("version", version), COLLECTION);
        return url(userId, version);
    }

    /** The user's icon URL, or null when they have none. */
    public String urlOf(String userId) {
        if (userId == null) return null;
        Document doc = mongo.findById(userId, Document.class, COLLECTION);
        return doc == null ? null : url(userId, ((Number) doc.get("version")).longValue());
    }

    public void delete(String userId) {
        r2.deleteRaw(key(userId));
        mongo.remove(Query.query(Criteria.where("_id").is(userId)), COLLECTION);
    }

    /** Every hour: the icons of users that no longer exist are deleted. */
    @Scheduled(initialDelay = 120_000, fixedDelay = 3_600_000)
    public void deleteOrphans() {
        // No users seen (another database, or it could not be read): nothing is deleted
        if (mongo.estimatedCount("users") == 0) return;
        for (Document doc : mongo.findAll(Document.class, COLLECTION)) {
            String userId = String.valueOf(doc.get("_id"));
            if (userExists(userId)) continue;
            try {
                delete(userId);
                log.info("Deleted the icon of removed user {}", userId);
            } catch (Exception e) {
                log.warn("Could not delete the icon of removed user {}: {}", userId, e.toString());
            }
        }
    }

    private boolean userExists(String userId) {
        Criteria byId = ObjectId.isValid(userId)
                ? new Criteria().orOperator(Criteria.where("_id").is(new ObjectId(userId)), Criteria.where("_id").is(userId))
                : Criteria.where("_id").is(userId);
        return mongo.exists(Query.query(byId), "users");
    }

    private String url(String userId, long version) {
        return publicUrl.strip().replaceAll("/+$", "") + "/" + key(userId) + "?v=" + version;
    }

    /** The image's centered square, scaled to SIZE, as a JPEG (transparency goes white). */
    static byte[] square(BufferedImage source) throws IOException {
        int side = Math.min(source.getWidth(), source.getHeight());
        int x = (source.getWidth() - side) / 2;
        int y = (source.getHeight() - side) / 2;
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, SIZE, SIZE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, SIZE, SIZE, x, y, x + side, y + side, null);
        g.dispose();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.88f);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(out, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
