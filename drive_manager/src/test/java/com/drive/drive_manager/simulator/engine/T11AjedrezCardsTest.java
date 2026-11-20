package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.PlayerState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cards in testing T11, archetype Ajedrez (2026-11-19): nine "Criatura Enlace" [cástelan]; one test (or more) per programmed effect, from
 * the printed text.
 */
@Tag("cards")
@Tag("t11")
class T11AjedrezCardsTest extends CardBatchSupport {

    /** A [cástelan] "Criatura Enlace" with arrows to both sides. */
    private static CardSlot piece(String id, String name, int cost, int level, int strength) {
        CardSlot c = withClasses(card(id, name, cost, level, "W"), "cástelan");
        c.setStrength(strength);
        c.setArrows(new ArrayList<>(List.of("left", "right")));
        c.setEnlace(true);
        return c;
    }

    private static CardSlot peonBlanco(String id) { return piece(id, "Peón Blanco de Cástelan", 1, 1, 3); }
    private static CardSlot peonNegro(String id) { return piece(id, "Peón Negro de Cástelan", 2, 1, 3); }
    private static CardSlot alfilBlanco(String id) { return piece(id, "Alfil Blanco de Cástelan", 3, 3, 6); }
    private static CardSlot alfilNegro(String id) { return piece(id, "Alfil Negro de Cástelan", 4, 3, 7); }
    private static CardSlot caballo(String id) { return piece(id, "Caballo Blanco de Cástelan", 3, 3, 7); }
    private static CardSlot torreBlanca(String id) { return piece(id, "Torre Blanca de Cástelan", 5, 4, 11); }
    private static CardSlot torreNegra(String id) { return piece(id, "Torre Negra de Cástelan", 6, 4, 10); }
    private static CardSlot reina(String id) { return piece(id, "Reina Blanca de Cástelan", 10, 5, 10); }
    private static CardSlot rey(String id) { return piece(id, "Rey Blanco de Cástelan", 8, 5, 13); }

    private static CardSlot enemy(String id, int strength) {
        CardSlot c = card(id, "Enemigo " + id, 1, 1, "R");
        c.setStrength(strength);
        return c;
    }

    /** On the Línea frontal at that horizontal position (their order is the formation's). */
    private CardSlot front(PlayerState p, CardSlot c, double x) {
        onField(p, c);
        c.setX(x);
        return c;
    }

    private void formation(PlayerState p, String... ids) {
        EffectEngine.refreshContinuous(room);
        host.getFormationEntries().clear();
        guest.getFormationEntries().clear();
        p.getFormations().add(new ArrayList<>(List.of(ids)));
        EffectEngine.refreshContinuous(room);
    }

    private void activate(CardSlot card, String idStart) {
        String script = Chain.activatable(room, "host").stream()
                .filter(a -> card.getInstanceId().equals(a.instanceId()) && a.scriptId().startsWith(idStart))
                .map(Chain.Activatable::scriptId).findFirst().orElse(null);
        assertThat(script).as("se puede activar " + idStart).isNotNull();
        assertThat(Chain.activateByPlayer(room, "host", card.getInstanceId(), script)).isNull();
    }

    private boolean canActivate(CardSlot card, String idStart) {
        return Chain.activatable(room, "host").stream().anyMatch(a -> card.getInstanceId().equals(a.instanceId()) && a.scriptId().startsWith(idStart));
    }

    /** Answers "none", except the first option of an order of mandatory effects; a link entry keeps the creature where it is. */
    private void settle() {
        for (int i = 0; i < 40; i++) {
            Prompt p = room.getPrompt();
            if (p == null) {
                if (room.getCombat() == null) break;
                Chain.advance(room);
                continue;
            }
            if (PromptPurpose.ORDER.is(p.getPurpose())) answer(p, p.getValidIds().get(0));
            else Answers.answer(room, p.getPlayerId(), p.getId(), List.of(), List.of());
        }
    }

    private List<String> order() {
        return Formations.ordered(host).stream().map(CardSlot::getInstanceId).toList();
    }

    // ------------------------------------------------------------------ W01 Peón Blanco de Cástelan

    @Test
    void peonBlancoDrawsThreeAndTributesTwo() {
        summon(peonBlanco("pb"));
        answer(room.getPrompt(), host.getHand().get(0).getInstanceId(), host.getHand().get(1).getInstanceId());
        settle();
        assertThat(host.getHand()).hasSize(1);
        assertThat(host.getTributeZone()).hasSize(2);
    }

    @Test
    void peonBlancoMovesToTheFrontWithABlackCastelan() {
        CardSlot pb = onField(host, peonBlanco("pb"), BACK);
        assertThat(canActivate(pb, "peon-blanco:turno-aliado")).isFalse();
        onField(host, peonNegro("pn"), BACK);
        activate(pb, "peon-blanco:turno-aliado");
        settle();
        assertThat(EffectEngine.inFrontLine(pb)).isTrue();
    }

    @Test
    void peonBlancoLinkedDestroysALinkedAllyInsteadOnItsTurn() {
        CardSlot giver = front(host, peonBlanco("pb"), 10);
        CardSlot receiver = front(host, alfilBlanco("ab"), 20);
        CardSlot third = front(host, caballo("cb"), 30);
        formation(host, "pb", "ab", "cb");
        Removals.destroy(room, receiver, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
        Prompt p = room.getPrompt();
        answer(p, p.getValidIds().get(0));
        assertThat(PromptPurpose.REPLACE_DESTROY.is(room.getPrompt().getPurpose())).isTrue();
        assertThat(room.getPrompt().getValidIds()).containsExactlyInAnyOrder("pb", "cb");
        answer("cb");
        settle();
        assertThat(host.getField()).contains(receiver, giver).doesNotContain(third);
    }

    @Test
    void peonBlancoLinkedDoesNotReplaceOnTheOpponentsTurn() {
        front(host, peonBlanco("pb"), 10);
        CardSlot receiver = front(host, alfilBlanco("ab"), 20);
        formation(host, "pb", "ab");
        room.setCurrentTurnUserId("guest");
        Removals.destroy(room, receiver, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
        settle();
        assertThat(host.getField()).doesNotContain(receiver);
    }

    // ------------------------------------------------------------------ W02 Peón Negro de Cástelan

    @Test
    void peonNegroComesFromTheTributosWithAPawnOnTheField() {
        CardSlot pn = peonNegro("pn");
        host.getTributeZone().add(pn);
        host.getHand().add(card("h1", "Mano", 1, 1, "R"));
        onField(host, peonBlanco("pb"), BACK);
        activate(pn, "peon-negro:turno-aliado-en-tributos");
        answer("h1");
        settle();
        assertThat(host.getField()).contains(pn);
        assertThat(EffectEngine.inFrontLine(pn)).isTrue();
        assertThat(gems()).isEqualTo(8);
    }

    @Test
    void peonNegroComesFromTheTributosWhenOnlyTheOpponentHasCreatures() {
        CardSlot pn = peonNegro("pn");
        host.getTributeZone().add(pn);
        host.getHand().add(card("h1", "Mano", 1, 1, "R"));
        assertThat(canActivate(pn, "peon-negro")).isFalse();
        onField(guest, enemy("en", 3));
        activate(pn, "peon-negro:turno-aliado-en-tributos");
        answer("h1");
        settle();
        assertThat(host.getField()).contains(pn);
    }

    @Test
    void peonNegroLinkedTributesALinkedAllyToSummonACastelanForItsCost() {
        front(host, peonNegro("pn"), 10);
        CardSlot attacker = front(host, alfilNegro("an"), 20);
        CardSlot linked = front(host, caballo("cb"), 30);
        formation(host, "pn", "an", "cb");
        CardSlot torre = torreBlanca("tb");
        host.getHand().add(torre);
        assertThat(CombatRules.attackByPlayer(room, "host", "an", null)).isNull();
        untilOffered(attacker);
        accept(attacker);
        answer("cb");
        answer("tb");
        settle();
        assertThat(host.getTributeZone()).contains(linked);
        assertThat(host.getField()).contains(torre);
        // Torre Blanca 5 - Caballo 3
        assertThat(gems()).isEqualTo(8 - 2);
    }

    // ------------------------------------------------------------------ W03 Alfil Blanco de Cástelan

    @Test
    void alfilBlancoSummonsACastelanForOneLessOnAttack() {
        CardSlot alfil = front(host, alfilBlanco("ab"), 10);
        CardSlot pawn = peonNegro("pn");
        host.getHand().add(pawn);
        assertThat(CombatRules.attackByPlayer(room, "host", "ab", null)).isNull();
        untilOffered(alfil);
        accept(alfil);
        answer("pn");
        settle();
        assertThat(host.getField()).contains(pawn);
        assertThat(gems()).isEqualTo(8 - 1);
    }

    @Test
    void alfilBlancoLinkedMakesAnEnemyLoseTheReceiversStrength() {
        front(host, alfilBlanco("ab"), 10);
        CardSlot receiver = front(host, torreBlanca("tb"), 20);
        formation(host, "ab", "tb");
        CardSlot target = onField(guest, enemy("en", 15));
        assertThat(CombatRules.attackByPlayer(room, "host", "tb", null)).isNull();
        untilOffered(receiver);
        accept(receiver);
        answer("en");
        assertThat(EffectEngine.strength(target)).isEqualTo(4);
    }

    // ------------------------------------------------------------------ W04 Alfil Negro de Cástelan

    @Test
    void alfilNegroComesFromTheTributosWhenAnEnemyIsDestroyedByEffect() {
        CardSlot alfil = alfilNegro("an");
        host.getTributeZone().add(alfil);
        CardSlot fodder = onField(host, card("fo", "Aliado", 1, 1, "R"), BACK);
        CardSlot victim = onField(guest, enemy("en", 2));
        victim.setTapped(true);
        front(host, enemy("at", 6), 10);
        assertThat(CombatRules.attackByPlayer(room, "host", "at", "en")).isNull();
        untilOffered(alfil);
        accept(alfil);
        answer("fo");
        settle();
        assertThat(host.getField()).contains(alfil).doesNotContain(fodder);
        assertThat(EffectEngine.inFrontLine(alfil)).isTrue();
    }

    @Test
    void alfilNegroLinkedTributesTheReceiverForAGemAtTheEndOfTheAttack() {
        front(host, alfilNegro("an"), 10);
        CardSlot receiver = front(host, torreBlanca("tb"), 20);
        formation(host, "an", "tb");
        assertThat(CombatRules.attackByPlayer(room, "host", "tb", null)).isNull();
        untilOffered(receiver);
        accept(receiver);
        settle();
        assertThat(host.getTributeZone()).contains(receiver);
        assertThat(gems()).isEqualTo(9);
    }

    // ------------------------------------------------------------------ W05 Caballo Blanco de Cástelan

    @Test
    void caballoSwapsWithAnotherAllyLinkedToTheChosenOne() {
        CardSlot cab = front(host, caballo("cb"), 10);
        front(host, peonBlanco("p1"), 20);
        front(host, peonNegro("p2"), 30);
        formation(host, "cb", "p1", "p2");
        activate(cab, "caballo-blanco:turno-aliado");
        answer("p1");
        assertThat(room.getPrompt().getValidIds()).containsExactly("p2");
        answer("p2");
        settle();
        assertThat(order()).containsExactly("p2", "p1", "cb");
    }

    @Test
    void caballoLinkedGivesPerforante() {
        front(host, caballo("cb"), 10);
        CardSlot receiver = front(host, torreBlanca("tb"), 20);
        formation(host, "cb", "tb");
        assertThat(EffectEngine.keywords(receiver)).contains("Perforante");
    }

    // ------------------------------------------------------------------ W06 Torre Blanca de Cástelan

    @Test
    void torreBlancaSwapsWithTheKingAndSummonsACastelanFromTheTributosForThreeLess() {
        CardSlot torre = front(host, torreBlanca("tb"), 10);
        front(host, peonBlanco("pb"), 20);
        front(host, rey("rb"), 30);
        CardSlot fromTributes = alfilNegro("an");
        host.getTributeZone().add(fromTributes);
        activate(torre, "torre-blanca:turno-aliado");
        assertThat(room.getPrompt().getValidIds()).containsExactly("rb");
        answer("rb");
        answer(room.getPrompt(), room.getPrompt().getValidIds().get(0));
        answer("an");
        settle();
        assertThat(order().subList(0, 3)).containsExactly("rb", "pb", "tb");
        assertThat(host.getField()).contains(fromTributes);
        assertThat(gems()).isEqualTo(8 - 1);
    }

    @Test
    void torreBlancaLinkedGivesAlcance() {
        front(host, torreBlanca("tb"), 10);
        CardSlot receiver = front(host, caballo("cb"), 20);
        formation(host, "tb", "cb");
        assertThat(EffectEngine.keywords(receiver)).contains("Alcance");
    }

    // ------------------------------------------------------------------ W07 Torre Negra de Cástelan

    @Test
    void torreNegraComesFromTheTributosForTheCostOfAnExhaustedAlly() {
        CardSlot torre = torreNegra("tn");
        host.getTributeZone().add(torre);
        CardSlot tired = onField(host, alfilNegro("an"), BACK);
        tired.setTapped(true);
        activate(torre, "torre-negra:turno-aliado-en-tributos");
        answer("an");
        settle();
        assertThat(host.getField()).contains(torre);
        // 6 - 4
        assertThat(gems()).isEqualTo(8 - 2);
    }

    @Test
    void torreNegraLinkedDestroysALowerEnemyWhenTargetedByTheOpponent() {
        front(host, torreNegra("tn"), 10);
        CardSlot receiver = front(host, torreBlanca("tb"), 20);
        formation(host, "tn", "tb");
        CardSlot low = onField(guest, enemy("en", 5));
        EffectEngine.record(room, GameEvent.TARGET, "guest", receiver, null, null, null,
                new HashMap<>(Map.of("targetIds", List.of("tb"))), "elige");
        Chain.advance(room);
        untilOffered(receiver);
        accept(receiver);
        answer("en");
        settle();
        assertThat(guest.getDiscardPile()).contains(low);
    }

    // ------------------------------------------------------------------ W08 Reina Blanca de Cástelan

    @Test
    void reinaComesFromTheHandReturningTheAllyThatDealtDirectDamage() {
        CardSlot reina = reina("rb");
        host.getHand().add(reina);
        CardSlot attacker = front(host, alfilBlanco("ab"), 10);
        assertThat(CombatRules.attackByPlayer(room, "host", "ab", null)).isNull();
        untilOffered(reina);
        accept(reina);
        settle();
        assertThat(host.getHand()).contains(attacker);
        assertThat(host.getField()).contains(reina);
        assertThat(EffectEngine.inFrontLine(reina)).isTrue();
    }

    @Test
    void reinaPlacesTwoSmallCastelanOnTheFrontLine() {
        host.getHand().addAll(List.of(peonBlanco("p1"), peonNegro("p2")));
        CardSlot reina = reina("rb");
        host.setResourceCount(10);
        room.setSharedCounter(-10);
        summon(reina);
        untilOffered(reina);
        accept(reina);
        answer("p1", "p2");
        settle();
        assertThat(host.getField()).extracting(CardSlot::getInstanceId).contains("p1", "p2");
    }

    @Test
    void reinaLinkedReturnsALinkedAllyToReactivateTheReceiverAfterDirectDamage() {
        front(host, reina("rq"), 10);
        CardSlot receiver = front(host, torreBlanca("tb"), 20);
        CardSlot other = front(host, peonBlanco("pb"), 30);
        formation(host, "rq", "tb", "pb");
        assertThat(CombatRules.attackByPlayer(room, "host", "tb", null)).isNull();
        untilOffered(receiver);
        accept(receiver);
        answer("pb");
        settle();
        assertThat(host.getHand()).contains(other);
        assertThat(receiver.isTapped()).isFalse();
    }

    // ------------------------------------------------------------------ W09 Rey Blanco de Cástelan

    @Test
    void reyMovesUpAndAttacksWithoutExhausting() {
        CardSlot rey = rey("ry");
        summon(rey);
        answer(room.getPrompt(), room.getPrompt().getValidIds().get(0));
        settle();
        assertThat(EffectEngine.inFrontLine(rey)).isTrue();
        assertThat(rey.isTapped()).isFalse();
        assertThat(guest.getLifeStack()).hasSize(2);
    }

    @Test
    void reyLinkedTributesTheReceiverAtTheBlockStepToSummonACastelanForFree() {
        front(host, rey("ry"), 10);
        CardSlot receiver = front(host, peonBlanco("pb"), 20);
        formation(host, "ry", "pb");
        CardSlot torre = torreBlanca("tb");
        host.getHand().add(torre);
        assertThat(CombatRules.attackByPlayer(room, "host", "pb", null)).isNull();
        untilOffered(receiver);
        accept(receiver);
        answer("tb");
        settle();
        assertThat(host.getTributeZone()).contains(receiver);
        assertThat(host.getField()).contains(torre);
        assertThat(gems()).isEqualTo(8);
    }
}
