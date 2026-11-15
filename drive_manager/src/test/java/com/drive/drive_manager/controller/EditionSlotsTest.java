package com.drive.drive_manager.controller;

import com.drive.drive_manager.dto.EditionMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** An edition's slots (colors × cards per color) survive a save that does not send them. */
class EditionSlotsTest {

    private static EditionMetadata edition(List<String> colors, Integer perColor) {
        EditionMetadata e = new EditionMetadata();
        e.setEditionId("ST2");
        e.setColors(colors);
        e.setCardsPerColor(perColor);
        return e;
    }

    @Test
    void aSaveWithoutSlotsKeepsTheEditions() {
        EditionMetadata body = edition(null, null);
        EditionMetadataController.keepSlots(body, edition(List.of("G"), 18));
        assertThat(body.getColors()).containsExactly("G");
        assertThat(body.getCardsPerColor()).isEqualTo(18);
    }

    @Test
    void aSaveWithSlotsChangesThem() {
        EditionMetadata body = edition(List.of("G", "R"), 12);
        EditionMetadataController.keepSlots(body, edition(List.of("G"), 18));
        assertThat(body.getColors()).containsExactly("G", "R");
        assertThat(body.getCardsPerColor()).isEqualTo(12);
    }
}
