package com.drive.drive_manager.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaymatServiceTest {

    private static BufferedImage read(byte[] jpeg) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    @Test
    void aBigImageIsCutWideAndScaledDownTo1920() throws Exception {
        BufferedImage photo = new BufferedImage(4000, 3000, BufferedImage.TYPE_INT_RGB);
        BufferedImage mat = read(PlaymatService.wide(photo));
        assertEquals(1920, mat.getWidth());
        assertEquals(800, mat.getHeight());
    }

    @Test
    void aSmallImageIsCutWideButNeverScaledUp() throws Exception {
        BufferedImage small = new BufferedImage(1200, 1200, BufferedImage.TYPE_INT_RGB);
        BufferedImage mat = read(PlaymatService.wide(small));
        assertEquals(1200, mat.getWidth());
        assertEquals(500, mat.getHeight());
    }

    @Test
    void aVeryWideImageKeepsItsHeightAndLosesItsSides() throws Exception {
        BufferedImage banner = new BufferedImage(3000, 500, BufferedImage.TYPE_INT_RGB);
        BufferedImage mat = read(PlaymatService.wide(banner));
        assertEquals(1200, mat.getWidth());
        assertEquals(500, mat.getHeight());
    }
}
