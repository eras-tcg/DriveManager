package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** E1 Violeta (licuado del 2026-11-17): one test (or more) per effect programmed for the new cards, from the printed text. */
@Tag("cards")
@Tag("e1")
class E1VioletCardsTest extends CardBatchSupport {

    private static CardSlot creature(String id, String name, int cost, int level, String color, int strength) {
        CardSlot c = card(id, name, cost, level, color);
        c.setStrength(strength);
        return c;
    }

    private static CardSlot exhaustedEnemy(String id, int level, int strength) {
        CardSlot c = creature(id, "Enemigo " + id, 1, level, "R", strength);
        c.setTapped(true);
        return c;
    }

    @Test
    void everyRitualHasItsRequirementProgrammed() {
        for (String name : List.of("Merodeador", "Peleador del Inframundo", "Aullador", "Zoc", "Guardia Del Sepulcro", "Brujos Fantasmagóricos",
                "Murx", "Raxas", "Necroquinox", "Halx", "Guardían de la Luna Sepulcral", "Zagatzu")) {
            CardSlot c = new CardSlot();
            c.setCardName(name);
            assertThat(CardEffects.requirementOf(c)).as(name).isNotNull();
        }
    }

    @Test
    void caballeroOxidadoDiscardsOneToTakeAVioletFromTheDiscard() {
        CardSlot caballero = inHand("co", "Caballero Oxidado", 1, 1, "P");
        CardSlot red = inHand("rh", "Roja De Mano", 1, 1, "R");
        CardSlot violet = card("dv", "Violeta En Descarte", 1, 1, "P");
        host.getDiscardPile().addAll(List.of(violet, card("dr", "Roja En Descarte", 1, 1, "R")));
        summon(caballero);
        accept(caballero);
        answer("rh");
        Prompt take = room.getPrompt();
        assertThat(take.getValidIds()).containsExactly("dv");
        answer(take, "dv");
        assertThat(host.getHand()).contains(violet).doesNotContain(red);
    }

    @Test
    void rataPosonozaDestroysALevelOneEnemyWhenDestroyed() {
        onField(host, creature("ra", "Rata Posoñoza", 1, 1, "P", 3), FRONT);
        onField(guest, exhaustedEnemy("big", 3, 9), FRONT);
        CardSlot one = onField(guest, creature("l1", "Nivel 1", 1, 1, "R", 2), FRONT);
        CardSlot two = onField(guest, creature("l2", "Nivel 2", 2, 2, "R", 4), FRONT);
        assertThat(CombatRules.attackByPlayer(room, "host", "ra", "big")).isNull();
        for (int i = 0; i < 30 && (room.getPrompt() != null || room.getCombat() != null); i++) {
            Prompt p = room.getPrompt();
            if (p == null) {
                Chain.advance(room);
                continue;
            }
            if ("host".equals(p.getPlayerId()) && p.getValidIds().contains("l1")) {
                assertThat(p.getValidIds()).doesNotContain("l2");
                answer(p, "l1");
            } else Answers.answer(room, p.getPlayerId(), p.getId(), List.of(), List.of());
        }
        assertThat(guest.getField()).doesNotContain(one).contains(two);
    }

    @Test
    void manchaDeSombrasTributesACopyInTheHandToGoToTheRetaguardia() {
        CardSlot mancha = inHand("m1", "Mancha de Sombras", 1, 1, "P");
        CardSlot copy = inHand("m2", "Mancha de Sombras", 1, 1, "P");
        assertThat(Chain.activateByPlayer(room, "host", "m1", "mancha-de-sombras:en-mano")).isNull();
        if (room.getPrompt() != null) answer("m2");
        drain();
        assertThat(host.getField()).contains(mancha);
        assertThat(EffectEngine.inFrontLine(mancha)).isFalse();
        assertThat(host.getTributeZone()).contains(copy);
    }

    @Test
    void peleadorDelInframundoDestroyedInAClashComesBackToTheFrontAtTheEndOfTheTurn() {
        CardSlot peleador = onField(host, creature("pe", "Peleador del Inframundo", 2, 2, "P", 4), FRONT);
        CardSlot fodder = inHand("fd", "Descarte", 1, 1, "R");
        onField(guest, exhaustedEnemy("big", 3, 9), FRONT);
        assertThat(CombatRules.attackByPlayer(room, "host", "pe", "big")).isNull();
        untilOffered(peleador);
        accept(peleador);
        if (room.getPrompt() != null && room.getPrompt().getValidIds().contains("fd")) answer("fd");
        drain();
        assertThat(host.getField()).doesNotContain(peleador);
        assertThat(host.getDiscardPile()).contains(fodder);
        TurnRules.turnEnding(room, "host");
        drain();
        assertThat(host.getField()).contains(peleador);
        assertThat(EffectEngine.inFrontLine(peleador)).isTrue();
    }

    @Test
    void retadorImbatibleSendsFiveVioletsFromTheDiscardToSummonItselfToTheFrontForOneLess() {
        CardSlot retador = inHand("ri", "Retador Imbatible", 4, 4, "P");
        retador.setStrength(11);
        for (int i = 0; i < 5; i++) host.getDiscardPile().add(card("d" + i, "Violeta " + i, 1, 1, "P"));
        int before = gems();
        assertThat(Chain.activateByPlayer(room, "host", "ri", "retador-imbatible:en-mano")).isNull();
        answer("d0", "d1", "d2", "d3", "d4");
        drain();
        assertThat(host.getField()).contains(retador);
        assertThat(EffectEngine.inFrontLine(retador)).isTrue();
        assertThat(gems()).isEqualTo(before - 3);
    }

    @Test
    void zagatzuDestroysAnEnemyOfLevelFourOrLessWhenSummoned() {
        CardSlot zagatzu = creature("za", "Zagatzu", 6, 5, "P", 14);
        CardSlot four = onField(guest, creature("l4", "Nivel 4", 4, 4, "R", 10), FRONT);
        CardSlot five = onField(guest, creature("l5", "Nivel 5", 5, 5, "R", 12), FRONT);
        summonByEffectToFront(zagatzu);
        Prompt target = room.getPrompt();
        if (target != null) {
            assertThat(target.getValidIds()).contains("l4").doesNotContain("l5");
            answer(target, "l4");
        }
        assertThat(guest.getField()).doesNotContain(four).contains(five);
    }

    @Test
    void infestacionMovesUpToTwoAlliesFromTheRetaguardiaToTheFront() {
        CardSlot a = onField(host, creature("a", "Aliado A", 1, 1, "P", 2), BACK);
        CardSlot b = onField(host, creature("b", "Aliado B", 1, 1, "P", 2), BACK);
        CardSlot c = onField(host, creature("c", "Aliado C", 1, 1, "P", 2), BACK);
        play(utility("in", "Infestación", 1, "P"));
        answer("a", "b");
        drain();
        assertThat(EffectEngine.inFrontLine(a)).isTrue();
        assertThat(EffectEngine.inFrontLine(b)).isTrue();
        assertThat(EffectEngine.inFrontLine(c)).isFalse();
    }
}
