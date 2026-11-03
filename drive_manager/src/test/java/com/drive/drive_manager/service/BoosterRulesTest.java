package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.EditionMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** What a pack or a box gives, drawn on the server. */
class BoosterRulesTest {

    private static final Map<String, List<String>> POOL = Map.of("C", List.of("c1", "c2"), "SR", List.of("sr1"), "SEC", List.of("sec1"));

    private static EditionMetadata.PackTypeConfig pack(String name, Map<String, Integer> perRarity) {
        return new EditionMetadata.PackTypeConfig(name, perRarity);
    }

    private static EditionMetadata edition(String id) {
        EditionMetadata e = new EditionMetadata();
        e.setEditionId(id);
        e.setPackTypes(List.of(pack("R PACK", Map.of("C", 3)), pack("SR PACK", Map.of("C", 2, "SR", 1)), pack("SEC PACK", Map.of("C", 2, "SEC", 1))));
        e.setBoxConfig(new EditionMetadata.BoxConfig(4, List.of(
                new EditionMetadata.BoxEntry("SR PACK", "FIXED", 1, null),
                new EditionMetadata.BoxEntry("SEC PACK", "PROB", null, List.of(new EditionMetadata.ProbOption(0, 0.5), new EditionMetadata.ProbOption(1, 0.5))),
                new EditionMetadata.BoxEntry("R PACK", "FLEX", null, null))));
        return e;
    }

    private static DoubleSupplier always(double value) {
        return () -> value;
    }

    @Test
    void aPackDrawsItsSlotsByRarity() {
        assertThat(BoosterRules.openPack(pack("SR PACK", Map.of("C", 2, "SR", 1)), POOL, always(0))).containsExactly("c1", "c1", "sr1");
    }

    @Test
    void aBoxPlacesFixedThenRolledThenFillsTheRest() {
        assertThat(BoosterRules.boxPackTypes(edition("E1"), always(0.9))).containsExactly("SR PACK", "SEC PACK", "R PACK", "R PACK");
        assertThat(BoosterRules.boxPackTypes(edition("E1"), always(0.1))).containsExactly("SR PACK", "R PACK", "R PACK", "R PACK");
        assertThat(BoosterRules.openBoxPacks(edition("E1"), POOL, always(0.1))).hasSize(4);
    }

    @Test
    void godPacksAreNamedSoOrBringManySecrets() {
        assertThat(BoosterRules.tier(pack("GOD PACK", Map.of("C", 5)))).isEqualTo(3);
        assertThat(BoosterRules.tier(pack("X", Map.of("SEC", 2)))).isEqualTo(3);
        assertThat(BoosterRules.tier(pack("X", Map.of("SEC", 1)))).isEqualTo(2);
        assertThat(BoosterRules.tier(pack("X", Map.of("SR", 1)))).isEqualTo(1);
        assertThat(BoosterRules.tier(pack("X", Map.of("C", 9)))).isZero();
    }

    @Test
    void oneBoxPackGoesUpARarity() {
        List<BoosterRules.OpenedPack> packs = List.of(new BoosterRules.OpenedPack("R PACK", List.of("c1")));

        List<BoosterRules.OpenedPack> upgraded = BoosterRules.upgradeOnePack(packs, edition("E1"), POOL, 1, always(0));

        assertThat(upgraded.get(0).packType()).isEqualTo("SR PACK");
        assertThat(upgraded.get(0).upgradedFrom()).isEqualTo("R PACK");
        // Missed the chance: unchanged
        assertThat(BoosterRules.upgradeOnePack(packs, edition("E1.1"), POOL, 0.5, always(0.7))).isEqualTo(packs);
    }
}
