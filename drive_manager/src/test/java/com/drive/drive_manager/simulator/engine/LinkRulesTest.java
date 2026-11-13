package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.ActionType;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameStateUpdate;
import com.drive.drive_manager.simulator.room.PlayerState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enlace (reglas en desarrollo, designer 2026-10-25 and 2026-11-13): order of the Línea frontal, admission, persistence, the link effects
 * given by active members, their refresh, the row closing up, the start of the turn and Ascenso.
 */
class LinkRulesTest extends CardBatchSupport {

    static final String[] BOTH = {"left", "right"};

    /** A creature with arrows ("Criatura Enlace"), [paladín], cost and level `level`. */
    static CardSlot linked(String id, String name, int level, String... arrows) {
        CardSlot c = withClasses(card(id, name, level, level, "R"), "paladín");
        c.setArrows(new ArrayList<>(List.of(arrows)));
        c.setEnlace(true);
        return c;
    }

    /** What the rules see after the table is set: no creature is taken as one that just entered the Línea frontal. */
    private void settle() {
        EffectEngine.refreshContinuous(room);
        host.getFormationEntries().clear();
        guest.getFormationEntries().clear();
    }

    private String join(String id, String target) {
        return Formations.joinByPlayer(room, "host", id, target);
    }

    private List<String> order(PlayerState p) {
        return Formations.ordered(p).stream().map(CardSlot::getInstanceId).toList();
    }

    private void tap(CardSlot c, boolean tapped) {
        c.setTapped(tapped);
        EffectEngine.record(room, tapped ? GameEvent.TAP : GameEvent.UNTAP, "host", c, null, null, null, null, null);
        Chain.advance(room);
        // A lone mandatory effect may ask to be confirmed
        for (int i = 0; i < 5 && room.getPrompt() != null && room.getPrompt().getMin() > 0; i++) answer(room.getPrompt().getValidIds().get(0));
    }

    // ------------------------------------------------------------------ Admission

    @Test
    void twoLevelOneCreaturesCannotLink() {
        onField(host, linked("a", "A", 1, BOTH));
        onField(host, linked("b", "B", 1, BOTH));
        settle();
        assertThat(Formations.joinOptions(room, "host")).isEmpty();
        assertThat(join("b", "a")).isNotNull();
        assertThat(host.getFormations()).isEmpty();
    }

    @Test
    void theSizeIsAtMostTheHighestLevelAmongTheMembersAndTheEnteringOne() {
        onField(host, linked("a", "A", 2, BOTH));
        onField(host, linked("b", "B", 1, BOTH));
        onField(host, linked("c", "C", 1, BOTH));
        settle();
        assertThat(join("b", "a")).isNull();
        assertThat(host.getFormations()).containsExactly(List.of("a", "b"));
        // A third one of level 1: 3 > 2
        assertThat(join("c", "b")).isNotNull();
        // A level 3 one counts its own level
        host.getField().get(2).setLevel(3);
        assertThat(join("c", "b")).isNull();
        assertThat(host.getFormations()).containsExactly(List.of("a", "b", "c"));
    }

    @Test
    void aFormationOfFourWithALevelFourAdmitsALevelFiveButNotALevelOne() {
        onField(host, linked("w", "W", 1, BOTH));
        onField(host, linked("x", "X", 4, BOTH));
        onField(host, linked("y1", "Y1", 1, BOTH));
        onField(host, linked("y2", "Y2", 1, BOTH));
        onField(host, linked("y3", "Y3", 1, BOTH));
        onField(host, linked("z", "Z", 5, BOTH));
        settle();
        assertThat(join("y1", "x")).isNull();
        assertThat(join("y2", "y1")).isNull();
        assertThat(join("y3", "y2")).isNull();
        // W (level 1) cannot join the formation of 4 (it would need a level 5); Z can
        assertThat(Formations.joinOptions(room, "host").getOrDefault("w", List.of())).doesNotContain("x", "y1", "y2", "y3");
        assertThat(Formations.joinOptions(room, "host").get("z")).contains("y3");
        assertThat(join("w", "x")).isNotNull();
        assertThat(join("z", "y3")).isNull();
        assertThat(host.getFormations()).containsExactly(List.of("x", "y1", "y2", "y3", "z"));
    }

    @Test
    void anExhaustedCreatureHasNoArrowsButCanBeAdmittedWhenPointedAt() {
        CardSlot a = onField(host, linked("a", "A", 2, "right"));
        CardSlot b = onField(host, linked("b", "B", 1));
        a.setTapped(true);
        settle();
        // B has no arrows and A, exhausted, points to nothing
        assertThat(join("b", "a")).isNotNull();
        a.setTapped(false);
        b.setTapped(true);
        // A points to B: B, exhausted and without arrows, is admitted
        assertThat(join("b", "a")).isNull();
        assertThat(host.getFormations()).containsExactly(List.of("a", "b"));
    }

    @Test
    void aFormationStaysWhenArrowsOrLevelsChange() {
        CardSlot a = onField(host, linked("a", "A", 2, BOTH));
        onField(host, linked("b", "B", 1, BOTH));
        settle();
        assertThat(join("b", "a")).isNull();
        tap(a, true);
        a.setLevel(1);
        a.setArrows(new ArrayList<>());
        EffectEngine.refreshContinuous(room);
        assertThat(host.getFormations()).containsExactly(List.of("a", "b"));
    }

    // ------------------------------------------------------------------ Link effects

    @Test
    void anActiveMemberGivesItsLinkEffectOnlyToWhoItPointsToAndExhaustedItDoesNot() {
        CardSlot peon = onField(host, linked("pe", "Peón de las Armaduras Marfil", 1, BOTH));
        CardSlot r = onField(host, linked("r", "Receptor", 2, BOTH));
        settle();
        assertThat(join("r", "pe")).isNull();
        assertThat(EffectEngine.strength(r)).isEqualTo(6);
        // A source never gives its effect to itself
        assertThat(EffectEngine.strength(peon)).isEqualTo(3);
        assertThat(r.getFormationGrants()).hasSize(1).allMatch(g -> g.startsWith("pe|"));
        tap(peon, true);
        assertThat(EffectEngine.strength(r)).isEqualTo(3);
        assertThat(r.getFormationGrants()).isEmpty();
        tap(peon, false);
        assertThat(EffectEngine.strength(r)).isEqualTo(6);
    }

    @Test
    void aGrantedOncePerTurnIsFreshAfterTheLinkIsBrokenAndRestoredButNotAfterASwapThatKeepsIt() {
        CardSlot nefilia = onField(host, linked("ne", "Nefilia la Paladín Arcanista", 2, BOTH));
        CardSlot big = onField(host, linked("g", "Grande", 4, BOTH));
        settle();
        assertThat(join("g", "ne")).isNull();
        tap(big, true);
        assertThat(big.isTapped()).as("reactivada por el efecto de enlace").isFalse();
        tap(big, true);
        assertThat(big.isTapped()).as("una vez por turno").isTrue();
        // Swapping their positions keeps the relation (Nefilia points to both sides): no refresh
        Formations.swap(room, null, host, nefilia, big);
        assertThat(order(host)).containsExactly("g", "ne");
        big.setTapped(false);
        tap(big, true);
        assertThat(big.isTapped()).isTrue();
        // Nefilia exhausted and reactivated: broken and restored, a new link
        tap(nefilia, true);
        assertThat(big.getFormationGrants()).isEmpty();
        tap(nefilia, false);
        big.setTapped(false);
        tap(big, true);
        assertThat(big.isTapped()).as("el enlace restablecido se refresca").isFalse();
    }

    @Test
    void aGrantedEffectShowsThePictureOfTheMemberThatGivesIt() {
        CardSlot nefilia = onField(host, linked("ne", "Nefilia la Paladín Arcanista", 2, BOTH));
        nefilia.setImageUrl("https://img/nefilia.jpg");
        CardSlot big = onField(host, linked("g", "Grande", 4, BOTH));
        settle();
        assertThat(join("g", "ne")).isNull();
        EffectScript granted = CardEffects.of(big).stream().filter(sc -> sc.id() != null && sc.id().contains(Formations.VIA)).findFirst().orElseThrow();
        PendingEffect e = Chain.candidate(granted, "host", big, 0, room);
        assertThat(e.getInheritedFromName()).isEqualTo("Nefilia la Paladín Arcanista");
        assertThat(e.getInheritedFromImage()).isEqualTo("https://img/nefilia.jpg");
    }

    // ------------------------------------------------------------------ Leaving, the start of the turn, Ascenso

    @Test
    void aMemberThatLeavesTheFrontLineLeavesItsFormationAndTheRowClosesUp() {
        onField(host, linked("a", "A", 3, BOTH));
        CardSlot b = onField(host, linked("b", "B", 1, BOTH));
        CardSlot c = onField(host, linked("c", "C", 1, BOTH));
        settle();
        assertThat(join("b", "a")).isNull();
        assertThat(join("c", "b")).isNull();
        Removals.destroy(room, b, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
        assertThat(order(host)).containsExactly("a", "c");
        assertThat(host.getFormations()).containsExactly(List.of("a", "c"));
        // A formation of 1 is none
        Removals.destroy(room, c, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
        assertThat(host.getFormations()).isEmpty();
    }

    @Test
    void ownFormationsAreUndoneOnlyAtTheStartOfTheirTurn() {
        onField(host, linked("a", "A", 2, BOTH));
        onField(host, linked("b", "B", 1, BOTH));
        settle();
        assertThat(join("b", "a")).isNull();
        room.setCurrentTurnUserId("guest");
        TurnRules.turnStarted(room, "guest");
        assertThat(host.getFormations()).hasSize(1);
        room.setCurrentTurnUserId("host");
        TurnRules.turnStarted(room, "host");
        assertThat(host.getFormations()).isEmpty();
        // They may form it again
        assertThat(Formations.joinOptions(room, "host")).containsKey("b");
    }

    @Test
    void anAscendedCreatureTakesThePositionAndTheFormationOfItsSummoner() {
        onField(host, linked("l", "Izquierda", 1, BOTH));
        CardSlot a = onField(host, linked("a", "A", 2, BOTH));
        onField(host, linked("b", "B", 1, BOTH));
        settle();
        assertThat(join("b", "a")).isNull();
        CardSlot promoted = linked("asc", "Ascendida", 3, BOTH);
        promoted.setSpecialSummonKind("promotion");
        promoted.setSpecialCost(0);
        promoted.setRequirement("Ascenso: criatura nivel 2.");
        host.getHand().add(promoted);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "asc", "a")).isNull();
        assertThat(host.getField()).contains(promoted).doesNotContain(a);
        assertThat(order(host)).containsExactly("l", "asc", "b");
        assertThat(Formations.ordered(host)).extracting(CardSlot::getX).doesNotHaveDuplicates();
        assertThat(host.getFormations()).containsExactly(List.of("asc", "b"));
        assertThat(room.getPrompt() == null || !PromptPurpose.FORMATION_ENTRY.is(room.getPrompt().getPurpose())).isTrue();
    }

    // ------------------------------------------------------------------ Entering the Línea frontal

    @Test
    void aCreatureThatEntersTheFrontLineChoosesItsPositionAndItsFormation() {
        onField(host, linked("a", "A", 2, BOTH));
        CardSlot e = onField(host, linked("e", "Entra", 1, BOTH), BACK);
        settle();
        TurnRules.offerAdvance(room, "host", true);
        answer("e");
        Prompt entry = room.getPrompt();
        assertThat(entry.getPurpose()).isEqualTo(PromptPurpose.FORMATION_ENTRY.id());
        assertThat(entry.getSubjectId()).isEqualTo("e");
        assertThat(entry.getValidIds()).containsExactly("pos:0", "pos:0:a", "pos:1", "pos:1:a");
        assertThat(entry.getCardLabels().get("pos:0:a")).contains("forma una formación con A");
        answer("pos:0:a");
        assertThat(order(host)).containsExactly("e", "a");
        assertThat(host.getFormations()).containsExactly(List.of("a", "e"));
        assertThat(Formations.ordered(host)).extracting(CardSlot::getX).doesNotHaveDuplicates();
        assertThat(EffectEngine.inFrontLine(e)).isTrue();
    }

    @Test
    void aCreatureMovedByHandToTheFrontLineIsAskedAtOnceNotAtTheNextAction() {
        onField(host, linked("a", "A", 2, BOTH));
        onField(host, linked("e", "Entra", 3, BOTH), BACK);
        settle();
        com.drive.drive_manager.simulator.room.GameAction move = new com.drive.drive_manager.simulator.room.GameAction();
        move.setType("MOVE_CARD");
        move.setPayload(new java.util.HashMap<>(Map.of("instanceId", "e", "fromZone", "field", "toZone", "field", "x", 70.0, "y", 25.0)));
        com.drive.drive_manager.simulator.actions.ZoneActions.handleMove(room, "host", move);
        assertThat(room.getPrompt()).isNotNull();
        assertThat(room.getPrompt().getPurpose()).isEqualTo(PromptPurpose.FORMATION_ENTRY.id());
        assertThat(room.getPrompt().getSubjectId()).isEqualTo("e");
    }

    @Test
    void aCreatureMovedByHandBetweenTwoMembersGoesToTheEndOfTheRow() {
        onField(host, linked("a", "A", 2, BOTH));
        onField(host, linked("b", "B", 2, BOTH));
        CardSlot c = onField(host, card("c", "Suelta", 1, 1, "R"));
        settle();
        assertThat(join("b", "a")).isNull();
        Formations.placeAt(host, c, 2);
        settle();
        com.drive.drive_manager.simulator.room.GameAction move = new com.drive.drive_manager.simulator.room.GameAction();
        move.setType("MOVE_CARD");
        double between = (Formations.ordered(host).get(0).getX() + Formations.ordered(host).get(1).getX()) / 2;
        move.setPayload(new java.util.HashMap<>(Map.of("instanceId", "c", "fromZone", "field", "toZone", "field", "x", between, "y", 25.0)));
        com.drive.drive_manager.simulator.actions.ZoneActions.handleMove(room, "host", move);
        assertThat(order(host).get(2)).isEqualTo("c");
        assertThat(host.getFormations()).hasSize(1);
    }

    @Test
    void aCreatureCannotStayBetweenTwoMembersOfAFormationItDoesNotJoin() {
        CardSlot a = onField(host, linked("a", "A", 3, BOTH));
        CardSlot b = onField(host, linked("b", "B", 1, BOTH));
        CardSlot c = onField(host, linked("c", "C", 1, BOTH), BACK);
        a.setX(10.0);
        b.setX(30.0);
        c.setX(20.0);
        settle();
        assertThat(join("b", "a")).isNull();
        TurnRules.offerAdvance(room, "host", true);
        answer("c");
        Prompt entry = room.getPrompt();
        // Between A and B only joining them
        assertThat(entry.getValidIds()).contains("pos:1:a").doesNotContain("pos:1");
        answer(entry);
        // It stays out: at the end of the row
        assertThat(order(host)).containsExactly("a", "b", "c");
        assertThat(host.getFormations()).containsExactly(List.of("a", "b"));
    }

    @Test
    void theBotsDoNotLinkAndAGameWithoutEnlaceIsNotAsked() {
        onField(host, card("p", "Plana", 2, 2, "R"));
        CardSlot q = onField(host, card("q", "Plana Dos", 1, 1, "R"), BACK);
        settle();
        TurnRules.offerAdvance(room, "host", true);
        answer("q");
        assertThat(room.getPrompt()).isNull();
        assertThat(EffectEngine.inFrontLine(q)).isTrue();
        assertThat(q.getX()).isEqualTo(50.0);
    }

    // ------------------------------------------------------------------ What the board is sent

    @Test
    void theUpdateSaysWhatCanBeLinkedAndTheFormations() {
        onField(host, linked("a", "A", 2, BOTH));
        onField(host, linked("b", "B", 1, BOTH));
        settle();
        GameStateUpdate update = GameStateUpdate.from(room, ActionType.FORMATION_JOIN, "host");
        assertThat(update.getFormationJoins()).isEqualTo(Map.of("host", Map.of("a", List.of("b"), "b", List.of("a"))));
        assertThat(join("b", "a")).isNull();
        update = GameStateUpdate.from(room, ActionType.FORMATION_JOIN, "host");
        assertThat(update.getFormationJoins()).isNull();
        assertThat(update.getPlayers().get("host").getFormations()).containsExactly(List.of("a", "b"));
        assertThat(room.getTimeline()).anyMatch(ev -> GameEvent.FORMATION.equals(ev.getType()) && "b".equals(ev.getCardInstanceId()));
    }

    @Test
    void inTheMainPhaseACreatureLinksWithOneThatIsNotBesideItTakingItsPlace() {
        onField(host, linked("a", "A", 3, BOTH));
        onField(host, card("x", "Suelta", 1, 1, "R"));
        onField(host, linked("c", "C", 1, BOTH));
        settle();
        assertThat(order(host)).containsExactly("a", "x", "c");
        assertThat(Formations.joinOptions(room, "host").get("c")).contains("a");
        // Without a target: the FORMATION_ENTRY sheet, only with the places where it links
        assertThat(Formations.joinByPlayer(room, "host", "c", null)).isNull();
        assertThat(room.getPrompt().getPurpose()).isEqualTo(PromptPurpose.FORMATION_ENTRY.id());
        assertThat(room.getPrompt().getValidIds()).allMatch(o -> o.split(":").length == 3).contains("pos:1:a");
        answer("pos:1:a");
        assertThat(order(host)).containsExactly("a", "c", "x");
        assertThat(host.getFormations()).containsExactly(List.of("a", "c"));
    }

    @Test
    void aCreatureWaitingToChooseItsPlaceNeverCutsTheLinkEffectsOfAFormation() {
        CardSlot a = onField(host, linked("a", "A", 3, BOTH));
        CardSlot b = onField(host, linked("b", "B", 2, BOTH));
        CardSlot c = onField(host, linked("c", "C", 2, BOTH), BACK);
        a.setX(10.0);
        b.setX(30.0);
        c.setX(20.0);
        settle();
        assertThat(join("b", "a")).isNull();
        EffectEngine.refreshContinuous(room);
        assertThat(b.getFormationGrants()).isNotEmpty();
        // C goes up between A and B (Mano a Mano, an advance…): while its sheet is open the formation stays whole
        TurnRules.offerAdvance(room, "host", true);
        answer("c");
        assertThat(room.getPrompt().getPurpose()).isEqualTo(PromptPurpose.FORMATION_ENTRY.id());
        assertThat(order(host)).containsExactly("a", "b", "c");
        assertThat(b.getFormationGrants()).isNotEmpty();
        assertThat(a.getFormationGrants()).isNotEmpty();
    }

    @Test
    void theLinkIsChosenAsSoonAsItEntersBeforeTheEffectsItsEntranceTriggers() {
        onField(host, linked("a", "A", 2, BOTH));
        settle();
        // Peón (En Invocación: look at 5…) summoned by an effect into the Línea frontal next to A
        summonByEffectToFront(linked("pe", "Peón de las Armaduras Marfil", 1, BOTH));
        assertThat(room.getPrompt()).isNotNull();
        assertThat(room.getPrompt().getPurpose()).isEqualTo(PromptPurpose.FORMATION_ENTRY.id());
        assertThat(room.getPrompt().getSubjectId()).isEqualTo("pe");
        answer(room.getPrompt().getValidIds().stream().filter(o -> o.endsWith(":a")).findFirst().orElseThrow());
        assertThat(host.getFormations()).containsExactly(List.of("a", "pe"));
        // Then its own En Invocación goes on
        assertThat(room.getPrompt()).isNotNull();
        assertThat(room.getPrompt().getPurpose()).isNotEqualTo(PromptPurpose.FORMATION_ENTRY.id());
    }
}
