package com.drive.drive_manager.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class AvatarServiceTest {

    @Test
    void anUploadedImageBecomesItsCenteredSquareAt256Pixels() throws Exception {
        // A wide image: red on the left and right thirds, blue in the middle
        BufferedImage wide = new BufferedImage(600, 200, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 600; x++) {
            for (int y = 0; y < 200; y++) wide.setRGB(x, y, (x >= 200 && x < 400 ? Color.BLUE : Color.RED).getRGB());
        }

        BufferedImage icon = ImageIO.read(new ByteArrayInputStream(AvatarService.square(wide)));

        assertThat(icon.getWidth()).isEqualTo(AvatarService.SIZE);
        assertThat(icon.getHeight()).isEqualTo(AvatarService.SIZE);
        // Only the middle survives the crop
        Color center = new Color(icon.getRGB(128, 128));
        assertThat(center.getBlue()).isGreaterThan(200);
        assertThat(center.getRed()).isLessThan(60);
    }
}
