package com.drive.drive_manager.simulator.rank;

import com.drive.drive_manager.simulator.rank.RankRules.Standing;
import com.drive.drive_manager.simulator.rank.RankRules.Tier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The ranked ladder: gems, shields, series and Retador's points, and the hidden MMR. */
class RankRulesTest {

    private static Standing at(Tier tier, int gems, int shields) {
        return new Standing(1000, 20, 10, 10, tier, gems, shields, false, 0, 0, 0, tier);
    }

    private static Standing play(Standing s, boolean... results) {
        for (boolean won : results) s = RankRules.after(s, won, s.mmr()).after();
        return s;
    }

    @Test
    void aNewPlayerStartsInHierroAndFillsItsGemsToGoUp() {
        Standing s = Standing.start();
        assertThat(s.tier()).isEqualTo(Tier.IRON);
        s = play(s, true, true);
        assertThat(s.gems()).isEqualTo(2);
        s = play(s, true);
        assertThat(s.tier()).isEqualTo(Tier.BRONZE);
        assertThat(s.gems()).isZero();
        assertThat(s.peak()).isEqualTo(Tier.BRONZE);
    }

    @Test
    void inTheLowRanksALossCostsNothing() {
        Standing s = play(at(Tier.BRONZE, 2, 0), false, false, false, false);
        assertThat(s.tier()).isEqualTo(Tier.BRONZE);
        assertThat(s.gems()).isEqualTo(2);
        assertThat(play(at(Tier.IRON, 0, 0), false).tier()).isEqualTo(Tier.IRON);
    }

    @Test
    void reachingPlataGivesTwoShieldsThatSpareTheFallAtNoGems() {
        Standing s = play(at(Tier.BRONZE, 2, 0), true);
        assertThat(s.tier()).isEqualTo(Tier.SILVER);
        assertThat(s.shields()).isEqualTo(RankRules.SHIELDS);
        s = play(s, false);
        assertThat(s.tier()).isEqualTo(Tier.SILVER);
        assertThat(s.shields()).isEqualTo(1);
        s = play(s, false);
        assertThat(s.shields()).isZero();
        s = play(s, false);
        assertThat(s.tier()).isEqualTo(Tier.BRONZE);
        assertThat(s.gems()).isEqualTo(2);
    }

    @Test
    void inPlataALossTakesAGemBeforeTheShield() {
        Standing s = play(at(Tier.SILVER, 2, 2), false);
        assertThat(s.gems()).isEqualTo(1);
        assertThat(s.shields()).isEqualTo(2);
    }

    @Test
    void platinoFullStartsASeriesWonWithTwoWins() {
        Standing s = play(at(Tier.PLATINUM, 4, 0), true);
        assertThat(s.inSeries()).isTrue();
        assertThat(s.tier()).isEqualTo(Tier.PLATINUM);
        s = play(s, true, false);
        assertThat(s.inSeries()).isTrue();
        assertThat(s.seriesWins()).isEqualTo(1);
        assertThat(s.seriesLosses()).isEqualTo(1);
        s = play(s, true);
        assertThat(s.tier()).isEqualTo(Tier.DIAMOND);
        assertThat(s.inSeries()).isFalse();
    }

    @Test
    void aLostSeriesLeavesThreeGems() {
        Standing s = play(at(Tier.DIAMOND, 4, 0), true, false, false);
        assertThat(s.tier()).isEqualTo(Tier.DIAMOND);
        assertThat(s.inSeries()).isFalse();
        assertThat(s.gems()).isEqualTo(RankRules.AFTER_SERIES);
    }

    @Test
    void inTheHighRanksALossAtNoGemsGoesDown() {
        Standing s = play(at(Tier.PLATINUM, 0, 0), false);
        assertThat(s.tier()).isEqualTo(Tier.GOLD);
        assertThat(s.gems()).isEqualTo(3);
        assertThat(s.shields()).isEqualTo(RankRules.SHIELDS);
        assertThat(s.peak()).isEqualTo(Tier.PLATINUM);
    }

    @Test
    void retadorIsByPointsAndAStrongerRivalGivesMore() {
        Standing s = play(at(Tier.DIAMOND, 4, 0), true, true, true);
        assertThat(s.tier()).isEqualTo(Tier.CHALLENGER);
        assertThat(s.points()).isZero();
        RankRules.Change even = RankRules.after(s, true, s.mmr());
        RankRules.Change strong = RankRules.after(s, true, s.mmr() + 300);
        assertThat(even.after().points()).isEqualTo(20);
        assertThat(strong.after().points()).isGreaterThan(even.after().points());
        // Under zero, back to Diamante
        Standing fall = RankRules.after(s, false, s.mmr()).after();
        assertThat(fall.tier()).isEqualTo(Tier.DIAMOND);
        assertThat(fall.gems()).isEqualTo(RankRules.AFTER_SERIES);
    }

    @Test
    void theMmrMovesFasterInThePlacementGamesAndByTheRivalsStrength() {
        RankRules.Change first = RankRules.after(Standing.start(), true, 1000);
        assertThat(first.mmrDelta()).isEqualTo(20);
        RankRules.Change later = RankRules.after(at(Tier.GOLD, 1, 2), true, 1000);
        assertThat(later.mmrDelta()).isEqualTo(12);
        assertThat(RankRules.after(at(Tier.GOLD, 1, 2), true, 1400).mmrDelta()).isGreaterThan(later.mmrDelta());
        assertThat(first.after().games()).isEqualTo(1);
        assertThat(first.after().wins()).isEqualTo(1);
    }
}
