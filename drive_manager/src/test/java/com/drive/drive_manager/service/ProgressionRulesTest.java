package com.drive.drive_manager.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The welcome chain is claimed in order; milestones go up one tier per claim. */
class ProgressionRulesTest {

    private static final ProgressionRules.Facts NEW_PLAYER = new ProgressionRules.Facts(Map.of(), Set.of(), false, 0);

    @Test
    void aNewPlayerCanClaimOnlyTheFirstStep() {
        List<ProgressionRules.StepView> welcome = ProgressionRules.welcome(NEW_PLAYER, Set.of());

        assertThat(welcome.get(0)).isEqualTo(new ProgressionRules.StepView("structure", 0, 1, 100, false, false));
        assertThat(welcome.subList(1, welcome.size())).allMatch(ProgressionRules.StepView::locked);
        assertThat(ProgressionRules.refusal(ProgressionRules.step("how-to-play"), NEW_PLAYER, Set.of())).isEqualTo("Primero completa las misiones anteriores");
    }

    @Test
    void theChainStartsByTakingOneOfST1ToST4() {
        assertThat(ProgressionRules.refusal(ProgressionRules.step("structure"), NEW_PLAYER, Set.of())).isEqualTo("Esa misión todavía no está cumplida");

        var withStarter = new ProgressionRules.Facts(Map.of(), Set.of(), true, 0);
        assertThat(ProgressionRules.welcome(withStarter, Set.of()).get(0)).isEqualTo(new ProgressionRules.StepView("structure", 1, 1, 100, false, false));
        assertThat(ProgressionRules.refusal(ProgressionRules.step("structure"), withStarter, Set.of())).isNull();
    }

    @Test
    void aStepNotDoneYetIsRefusedAndOnlyADeckSavedCounts() {
        Set<String> claimed = Set.of("structure", "how-to-play", "cards");
        assertThat(ProgressionRules.refusal(ProgressionRules.step("deck"), NEW_PLAYER, claimed)).isEqualTo("Esa misión todavía no está cumplida");

        var withDeck = new ProgressionRules.Facts(Map.of(ProgressionRules.DECK_SAVED, 1), Set.of("como-jugar", "cartas"), true, 40);
        assertThat(ProgressionRules.refusal(ProgressionRules.step("deck"), withDeck, claimed)).isNull();
        assertThat(ProgressionRules.refusal(ProgressionRules.step("structure"), withDeck, claimed)).isEqualTo("Ya reclamaste esa misión");
    }

    @Test
    void theLastStepCountsTheOthersClaimed() {
        Set<String> claimed = new HashSet<>();
        ProgressionRules.WELCOME.stream().map(ProgressionRules.Step::id).filter(id -> !id.equals("graduate")).limit(7).forEach(claimed::add);
        var graduate = ProgressionRules.step("graduate");
        assertThat(ProgressionRules.progress(graduate, NEW_PLAYER, claimed)).isEqualTo(7);
        assertThat(ProgressionRules.refusal(graduate, NEW_PLAYER, claimed)).isEqualTo("Primero completa las misiones anteriores");

        claimed.add("first-win");
        assertThat(ProgressionRules.refusal(graduate, NEW_PLAYER, claimed)).isNull();
    }

    @Test
    void gamesAgainstTheBotCountForTheMilestonesButNotForTheFirstGameAgainstAPlayer() {
        Set<String> claimed = Set.of("structure", "how-to-play", "cards", "deck", "pack", "avatar");
        var firstGame = ProgressionRules.step("first-game");
        var onlyBot = new ProgressionRules.Facts(Map.of(MissionRules.GAME_PLAYED, 3, ProgressionRules.BOT_GAME_PLAYED, 3, MissionRules.GAME_WON, 2), Set.of(), false, 0);
        assertThat(ProgressionRules.progress(firstGame, onlyBot, claimed)).isZero();
        assertThat(ProgressionRules.refusal(firstGame, onlyBot, claimed)).isEqualTo("Esa misión todavía no está cumplida");
        assertThat(ProgressionRules.milestones(onlyBot, Map.of("games", 1)).get(0).progress()).isEqualTo(3);
        assertThat(ProgressionRules.progress(ProgressionRules.step("first-win"), onlyBot, Set.of())).isEqualTo(2);

        var oneAgainstAPlayer = new ProgressionRules.Facts(Map.of(MissionRules.GAME_PLAYED, 4, ProgressionRules.BOT_GAME_PLAYED, 3), Set.of(), false, 0);
        assertThat(ProgressionRules.refusal(firstGame, oneAgainstAPlayer, claimed)).isNull();
    }

    @Test
    void aMilestoneShowsItsNextTierAndStopsAtTheLast() {
        var facts = new ProgressionRules.Facts(Map.of(MissionRules.GAME_PLAYED, 12), Set.of(), false, 0);
        var games = ProgressionRules.milestone("games");

        assertThat(ProgressionRules.milestones(facts, Map.of("games", 1)).get(0)).isEqualTo(new ProgressionRules.MilestoneView("games", 1, 5, 10, 10, 250, false));
        assertThat(ProgressionRules.refusal(games, facts, 1)).isNull();
        assertThat(ProgressionRules.refusal(games, facts, 2)).isEqualTo("Ese nivel todavía no está cumplido");
        assertThat(ProgressionRules.refusal(games, facts, 5)).isEqualTo("Ya completaste todos los niveles de esa misión");
        assertThat(ProgressionRules.milestones(facts, Map.of("games", 5)).get(0)).isEqualTo(new ProgressionRules.MilestoneView("games", 5, 5, 12, 100, 2000, true));
    }

    @Test
    void everyMilestoneHasAsManyGemsAsTiersAndRisingTargets() {
        for (ProgressionRules.Milestone m : ProgressionRules.MILESTONES) {
            assertThat(m.gems()).as(m.id()).hasSameSizeAs(m.targets());
            for (int i = 1; i < m.targets().length; i++) assertThat(m.targets()[i]).as(m.id()).isGreaterThan(m.targets()[i - 1]);
        }
    }
}
