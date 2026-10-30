package com.drive.drive_manager.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Card file names: the exported .jpg loses the real hyphens; its .psd next to it keeps the printed name. */
class DriveParserTest {

    private final DriveParser parser = new DriveParser(null, "root", null);

    @Test
    void theFileNameAloneTurnsEveryHyphenIntoASpace() {
        DriveParser.ParsedFileName parsed = parser.parseFileName("E2-B17-Roto-G0.jpg");
        assertThat(parsed.name()).isEqualTo("Roto G0");
        assertThat(parsed.edition()).isEqualTo("E2");
        assertThat(parsed.number()).isEqualTo(17);
        // The file named with a space is not an exported card: the sync does not read it
        assertThat(parser.parseFileName("E2-B17 Roto-G0.jpg")).isNull();
    }

    @Test
    void thePsdNextToItGivesTheRealName() {
        Map<String, String> real = DriveParser.realNames(List.of(
                "E2-B17-Roto-G0.jpg", "E2-B17 Roto-G0.jpg", "E2-B17 Roto-G0.psd",
                "E1_1-B08 Arc-xioma.psd", "CRIATURA - PROTOTIPO.psd"));
        assertThat(real).containsOnly(Map.entry("E2-B17", "Roto-G0"), Map.entry("E1_1-B08", "Arc-xioma"));

        assertThat(DriveParser.withRealName(parser.parseFileName("E2-B17-Roto-G0.jpg"), "E2-B17-Roto-G0.jpg", real).name())
                .isEqualTo("Roto-G0");
        assertThat(DriveParser.withRealName(parser.parseFileName("E1_1-B08-Arc-xioma.jpg"), "E1_1-B08-Arc-xioma.jpg", real))
                .satisfies(p -> {
                    assertThat(p.name()).isEqualTo("Arc-xioma");
                    assertThat(p.subEdition()).isEqualTo("1");
                });
        // Without a .psd it keeps the file's name
        assertThat(DriveParser.withRealName(parser.parseFileName("E2-B02-Flerik.jpg"), "E2-B02-Flerik.jpg", real).name()).isEqualTo("Flerik");
    }
}
