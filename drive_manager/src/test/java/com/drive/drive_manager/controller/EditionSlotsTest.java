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

    @Test
    void eachColorKeepsItsOwnCount() {
        EditionMetadata existing = edition(List.of("B", "W"), 25);
        existing.setCardsByColor(java.util.Map.of("W", 5));
        EditionMetadata body = edition(null, null);
        EditionMetadataController.keepSlots(body, existing);
        assertThat(body.getCardsByColor()).containsEntry("W", 5);
        EditionMetadata changed = edition(List.of("B", "W"), 25);
        changed.setCardsByColor(java.util.Map.of("W", 4));
        EditionMetadataController.keepSlots(changed, existing);
        assertThat(changed.getCardsByColor()).containsEntry("W", 4);
    }

    @Test
    void aSaveWithoutTheCoverFramingKeepsIt() {
        EditionMetadata existing = edition(List.of("G"), 18);
        existing.setCoverFrame(new EditionMetadata.CoverFrame(30, 60, 1.5));
        EditionMetadata body = edition(null, null);
        EditionMetadataController.keepSlots(body, existing);
        assertThat(body.getCoverFrame().getZoom()).isEqualTo(1.5);
        EditionMetadata framed = edition(null, null);
        framed.setCoverFrame(new EditionMetadata.CoverFrame(50, 50, 1));
        EditionMetadataController.keepSlots(framed, existing);
        assertThat(framed.getCoverFrame().getX()).isEqualTo(50);
    }
}
