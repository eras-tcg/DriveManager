package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.AltArt;
import com.drive.drive_manager.dto.ShopOdds;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The copies with one of a card's alternative arts: kept apart, played first, never broken or counted as extras. */
class AltArtRulesTest {

    @Test
    void aCopyWithAnAlternativeArtIsKeptUnderItsOwnKey() {
        assertThat(CollectionRules.copyKey("foil", "x1")).isEqualTo("alt-x1-foil");
        assertThat(CollectionRules.copyKey("foil", null)).isEqualTo("foil");
        assertThat(CollectionRules.isAlt("alt-x1-prism")).isTrue();
        assertThat(CollectionRules.finishOf("alt-x1-prism")).isEqualTo("prism");
        assertThat(CollectionRules.artOf("alt-x1-prism")).isEqualTo("x1");
        assertThat(CollectionRules.finishOf("plain")).isEqualTo("plain");
        assertThat(CollectionRules.artOf("plain")).isNull();
    }

    @Test
    void theCopiesOfTheSingleArtOfBeforeKeepTheirKeys() {
        assertThat(CollectionRules.copyKey("foil", "0")).isEqualTo("alt-foil");
        assertThat(CollectionRules.artOf("alt-foil")).isEqualTo("0");
        assertThat(CollectionRules.finishOf("alt-foil")).isEqualTo("foil");
    }

    @Test
    void itIsNoFinishToBreakCraftOrChase() {
        assertThat(CollectionRules.isFinish("alt-plain")).isFalse();
        assertThat(CollectionRules.isFinish("alt-x1-plain")).isFalse();
        assertThat(CollectionRules.FINISHES).doesNotContain("alt-plain", "alt-foil", "alt-prism");
    }

    @Test
    void aDeckPlaysTheAlternativeArtsFirstTheFinestFinishFirst() {
        assertThat(CollectionRules.copyFinishes(Map.of("plain", 2, "prism", 1, "alt-plain", 1), 4))
                .containsExactly("alt-plain", "prism", "plain", "plain");
        assertThat(CollectionRules.copyFinishes(Map.of("alt-x1-plain", 1, "alt-y2-prism", 1, "foil", 1), 4))
                .containsExactly("alt-y2-prism", "alt-x1-plain", "foil", null);
    }

    @Test
    void theExtrasLeaveTheAlternativeArtsAlone() {
        assertThat(CollectionRules.extras(Map.of("plain", 5, "alt-plain", 3, "alt-x1-foil", 2))).isEqualTo(Map.of("plain", 1));
    }

    @Test
    void eachArtComesOutByItsOwnOddsOrTheShops() {
        List<AltArt> arts = List.of(new AltArt("a", "u1", null), new AltArt("b", "u2", 0.01));
        // The shop's 0.001 for "a", then 0.01 more for "b"
        assertThat(CollectionRules.pickArt(arts, 0.001, () -> 0.0005)).isEqualTo("a");
        assertThat(CollectionRules.pickArt(arts, 0.001, () -> 0.005)).isEqualTo("b");
        assertThat(CollectionRules.pickArt(arts, 0.001, () -> 0.02)).isNull();
        assertThat(CollectionRules.pickArt(List.of(), 1, () -> 0)).isNull();
        assertThat(CollectionRules.pickArt(null, 1, () -> 0)).isNull();
    }

    @Test
    void theFinishFollowsTheShopsOdds() {
        ShopOdds odds = new ShopOdds(ShopOdds.ID, 0.5, 0.1, 0, 1, 0.5);
        assertThat(CollectionRules.rollFinish(() -> 0.05, odds)).isEqualTo("prism");
        assertThat(CollectionRules.rollFinish(() -> 0.4, odds)).isEqualTo("foil");
        assertThat(CollectionRules.rollFinish(() -> 0.7, odds)).isEqualTo("plain");
    }
}
