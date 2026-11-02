package com.drive.drive_manager.service;

import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Finishes, fragments, crafting and extra copies. */
class CollectionRulesTest {

    private static DoubleSupplier rolls(Double... values) {
        Iterator<Double> it = List.of(values).iterator();
        return it::next;
    }

    @Test
    void finishesComeByTheirOdds() {
        assertThat(CollectionRules.rollFinish(() -> 0.05)).isEqualTo("prism");
        assertThat(CollectionRules.rollFinish(() -> 0.3)).isEqualTo("foil");
        assertThat(CollectionRules.rollFinish(() -> 0.9)).isEqualTo("plain");
    }

    @Test
    void aFinerCopyGivesMoreFragments() {
        assertThat(CollectionRules.fragmentsOf("plain")).isEqualTo(10);
        assertThat(CollectionRules.fragmentsOf("foil")).isEqualTo(30);
        assertThat(CollectionRules.fragmentsOf("prism")).isEqualTo(90);
    }

    @Test
    void craftingUntilAFinishStopsWhenItComesOrTheFragmentsRunOut() {
        // plain, foil, prism: stops at the prism
        assertThat(CollectionRules.craftUntil("prism", 300, rolls(0.9, 0.3, 0.05))).containsExactly("plain", "foil", "prism");
        // A foil is enough when aiming at foil
        assertThat(CollectionRules.craftUntil("foil", 300, rolls(0.9, 0.05))).containsExactly("plain", "prism");
        // Only two crafts' worth of fragments
        assertThat(CollectionRules.craftUntil("prism", 65, rolls(0.9, 0.9, 0.9))).containsExactly("plain", "plain");
        assertThat(CollectionRules.craftUntil("prism", 29, rolls(0.9))).isEmpty();
    }

    @Test
    void theFinestCopiesAreKeptAndTheRestAreExtra() {
        assertThat(CollectionRules.extras(Map.of("plain", 5, "foil", 1, "prism", 1))).containsExactly(Map.entry("plain", 3));
        assertThat(CollectionRules.extras(Map.of("foil", 6))).containsExactly(Map.entry("foil", 2));
        assertThat(CollectionRules.extras(Map.of("plain", 4))).isEmpty();
    }
}
