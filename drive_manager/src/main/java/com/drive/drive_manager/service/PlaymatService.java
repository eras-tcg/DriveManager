package com.drive.drive_manager.service;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Players' playmats: an image they upload, shown under their half of the simulator's table. Wide, as the half is: cut
 * to {@value #WIDTH}:{@value #HEIGHT} around its middle and scaled down to that size (never up), kept in R2 under
 * {@code playmats/<userId>.jpg}; which users have one is in {@code user_playmats}.
 */
@Service
public class PlaymatService extends UserImageStore {

    static final int WIDTH = 1920;
    static final int HEIGHT = 800;
    /** The largest file accepted, before scaling. */
    public static final int MAX_BYTES = 8 * 1024 * 1024;

    public PlaymatService(R2Service r2, MongoTemplate mongo) {
        super(r2, mongo, "playmats", "user_playmats");
    }

    @Override
    protected byte[] shape(BufferedImage source) throws IOException {
        return wide(source);
    }

    /** The image's centered {@value #WIDTH}:{@value #HEIGHT} part, at most that size, as a JPEG. */
    static byte[] wide(BufferedImage source) throws IOException {
        double ratio = (double) WIDTH / HEIGHT;
        int w = source.getWidth();
        int h = source.getHeight();
        // The widest part of that shape the image holds
        int cutW = Math.min(w, (int) Math.round(h * ratio));
        int cutH = Math.min(h, (int) Math.round(cutW / ratio));
        int x = (w - cutW) / 2;
        int y = (h - cutH) / 2;
        int outW = Math.min(WIDTH, cutW);
        int outH = Math.max(1, (int) Math.round(outW / ratio));
        BufferedImage out = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, outW, outH);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, outW, outH, x, y, x + cutW, y + cutH, null);
        g.dispose();
        return jpeg(out, 0.84f);
    }
}
