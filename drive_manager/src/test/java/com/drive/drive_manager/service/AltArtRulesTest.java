package com.drive.drive_manager.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The copies with a card's alternative art: kept apart, played first, never broken or counted as extras. */
class AltArtRulesTest {

    @Test
    void aCopyWithTheAlternativeArtIsKeptUnderItsOwnKey() {
        assertThat(CollectionRules.copyKey("foil", true)).isEqualTo("alt-foil");
        assertThat(CollectionRules.copyKey("foil", false)).isEqualTo("foil");
        assertThat(CollectionRules.isAlt("alt-prism")).isTrue();
        assertThat(CollectionRules.finishOf("alt-prism")).isEqualTo("prism");
        assertThat(CollectionRules.finishOf("plain")).isEqualTo("plain");
    }

    @Test
    void itIsNoFinishToBreakCraftOrChase() {
        assertThat(CollectionRules.isFinish("alt-plain")).isFalse();
        assertThat(CollectionRules.FINISHES).doesNotContain("alt-plain", "alt-foil", "alt-prism");
    }

    @Test
    void aDeckPlaysTheAlternativeArtFirst() {
        assertThat(CollectionRules.copyFinishes(Map.of("plain", 2, "prism", 1, "alt-plain", 1), 4))
                .containsExactly("alt-plain", "prism", "plain", "plain");
        assertThat(CollectionRules.copyFinishes(Map.of("alt-foil", 1), 2)).containsExactly("alt-foil", null);
    }

    @Test
    void theExtrasLeaveTheAlternativeArtAlone() {
        assertThat(CollectionRules.extras(Map.of("plain", 5, "alt-plain", 3))).isEqualTo(Map.of("plain", 1));
    }
}
