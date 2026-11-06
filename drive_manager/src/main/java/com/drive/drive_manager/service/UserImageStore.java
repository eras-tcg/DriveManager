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

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;

/**
 * An image a player uploads for themselves (their icon, their playmat): reshaped by {@link #shape}, kept in R2 under
 * {@code <folder>/<userId>.jpg}. Which users have one (and its version, for the URL) is in its own collection. The
 * images of users that no longer exist are deleted every hour.
 */
public abstract class UserImageStore {

    private static final Logger log = LoggerFactory.getLogger(UserImageStore.class);

    private final R2Service r2;
    private final MongoTemplate mongo;
    private final String folder;
    private final String collection;

    @Value("${r2.public-url:}")
    private String publicUrl;

    protected UserImageStore(R2Service r2, MongoTemplate mongo, String folder, String collection) {
        this.r2 = r2;
        this.mongo = mongo;
        this.folder = folder;
        this.collection = collection;
    }

    /** The image as it is kept, as a JPEG. */
    protected abstract byte[] shape(BufferedImage source) throws IOException;

    private String key(String userId) {
        return folder + "/" + userId + ".jpg";
    }

    /** Stores the user's image from the uploaded file and returns its URL. */
    public String put(String userId, byte[] image) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(image));
        if (source == null) throw new IllegalArgumentException("El archivo no es una imagen JPG, PNG, GIF o BMP");
        byte[] jpeg = shape(source);
        r2.uploadRaw(key(userId), new ByteArrayInputStream(jpeg), jpeg.length);
        long version = Instant.now().getEpochSecond();
        mongo.upsert(Query.query(Criteria.where("_id").is(userId)), Update.update("version", version), collection);
        return url(userId, version);
    }

    /** The user's image URL, or null when they have none. */
    public String urlOf(String userId) {
        if (userId == null) return null;
        Document doc = mongo.findById(userId, Document.class, collection);
        return doc == null ? null : url(userId, ((Number) doc.get("version")).longValue());
    }

    public void delete(String userId) {
        r2.deleteRaw(key(userId));
        mongo.remove(Query.query(Criteria.where("_id").is(userId)), collection);
    }

    /** Every hour: the images of users that no longer exist are deleted. */
    @Scheduled(initialDelay = 120_000, fixedDelay = 3_600_000)
    public void deleteOrphans() {
        // No users seen (another database, or it could not be read): nothing is deleted
        if (mongo.estimatedCount("users") == 0) return;
        for (Document doc : mongo.findAll(Document.class, collection)) {
            String userId = String.valueOf(doc.get("_id"));
            if (userExists(userId)) continue;
            try {
                delete(userId);
                log.info("Deleted the {} image of removed user {}", folder, userId);
            } catch (Exception e) {
                log.warn("Could not delete the {} image of removed user {}: {}", folder, userId, e.toString());
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

    /** The image as a JPEG of the given quality. */
    protected static byte[] jpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
