package com.drive.drive_manager.service;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Players' icons: an image they upload, cut to a square and scaled to {@value #SIZE} px, kept in R2 under
 * {@code avatars/<userId>.jpg}. Which users have one (and its version, for the URL) is in {@code user_avatars}.
 * When a user is deleted (UsersService, same database) the cleanup removes their icon.
 */
@Service
public class AvatarService extends UserImageStore {

    static final int SIZE = 256;
    /** The largest file accepted, before scaling. */
    public static final int MAX_BYTES = 5 * 1024 * 1024;

    public AvatarService(R2Service r2, MongoTemplate mongo) {
        super(r2, mongo, "avatars", "user_avatars");
    }

    @Override
    protected byte[] shape(BufferedImage source) throws IOException {
        return square(source);
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
        return jpeg(out, 0.88f);
    }
}
