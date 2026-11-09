package com.drive.drive_manager.service;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Players' profile banners: an image they upload, shown behind their icon and name at the top of their profile. Very
 * wide, as that box is: cut to {@value #WIDTH}:{@value #HEIGHT} around its middle and scaled down to that size (never
 * up), kept in R2 under {@code banners/<userId>.jpg}; which users have one is in {@code user_banners}.
 */
@Service
public class BannerService extends UserImageStore {

    static final int WIDTH = 1600;
    static final int HEIGHT = 400;
    /** The largest file accepted, before scaling. */
    public static final int MAX_BYTES = 8 * 1024 * 1024;

    public BannerService(R2Service r2, MongoTemplate mongo) {
        super(r2, mongo, "banners", "user_banners");
    }

    @Override
    protected byte[] shape(BufferedImage source) throws IOException {
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
