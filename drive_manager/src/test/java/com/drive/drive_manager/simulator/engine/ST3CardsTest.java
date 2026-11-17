package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** ST3 (Violeta, Ritual; estructura nueva del 2026-11-15): one test (or more) per programmed effect, from the printed text. */
class ST3CardsTest extends CardBatchSupport {

    private static CardSlot violet(String id, String name, int cost, int level, int strength) {
        CardSlot c = card(id, name, cost, level, "P");
        c.setStrength(strength);
        return c;
    }

    private static CardSlot enemy(String id, int level, int strength) {
        CardSlot c = card(id, "Enemigo " + id, level, level, "R");
        c.setStrength(strength);
        return c;
    }

    @Test
    void everyRitualHasItsRequirementProgrammed() {
        for (String name : List.of("Sombra Andante", "Bajorn Pútrido", "Guardia del Templo Abandonado", "Rey Muerto", "Stomfilus",
                "Armadura Perpetua", "Guardia Del Sepulcro", "Guardían de la Luna Sepulcral", "Hematófago Invernal", "Muerte Caminante")) {
            CardSlot c = new CardSlot();
            c.setCardName(name);
            assertThat(CardEffects.requirementOf(c)).as(name).isNotNull();
        }
    }

    @Test
    void guardianDeLaLunaSepulcralNeedsALevelThreeAndExactlyOneMoreAlly() {
        CardSlot guardian = violet("gl", "Guardían de la Luna Sepulcral", 5, 4, 11);
        guardian.setSpecialSummonKind("ritual");
        guardian.setSpecialCost(1);
        host.getHand().add(guardian);
        onField(host, violet("s", "Invocador", 3, 3, 8), FRONT);
        assertThat(SpecialRequirements.canSpecial(room, "host", guardian)).isFalse();
        onField(host, violet("a", "Aliado A", 1, 1, 2), BACK);
        onField(host, violet("b", "Aliado B", 2, 2, 4), BACK);
        assertThat(SpecialRequirements.canSpecial(room, "host", guardian)).isTrue();
        assertThat(SpecialSummons.specialByPlayer(room, "host", "gl", "s")).isNull();
        Prompt others = room.getPrompt();
        assertThat(others.getMax()).isEqualTo(1);
        answer(others, "b");
        for (int i = 0; i < 5 && room.getPrompt() != null && !host.getField().contains(guardian); i++) answer(room.getPrompt());
        assertThat(host.getField()).contains(guardian);
        assertThat(host.getField()).extracting(CardSlot::getInstanceId).contains("a").doesNotContain("s", "b");
    }

    @Test
    void plagaRabiosaSendsFiveVioletTributesToTheBottomToSummonItselfInTheFrontLine() {
        CardSlot plaga = violet("pl", "Plaga Rabiosa", 1, 1, 3);
        host.getTributeZone().add(plaga);
        for (int i = 0; i < 5; i++) host.getTributeZone().add(card("v" + i, "Violeta " + i, 1, 1, "P"));
        assertThat(Chain.activateByPlayer(room, "host", "pl", "plaga-rabiosa:turno-aliado-en-tributos")).isNull();
        Prompt send = room.getPrompt();
        assertThat(send.getValidIds()).doesNotContain("pl");
        answer(send, "v0", "v1", "v2", "v3", "v4");
        drain();
        assertThat(host.getField()).contains(plaga);
        assertThat(EffectEngine.inFrontLine(plaga)).isTrue();
    }

    @Test
    void plagaRabiosaNeedsFiveOtherVioletTributes() {
        host.getTributeZone().add(violet("pl", "Plaga Rabiosa", 1, 1, 3));
        for (int i = 0; i < 4; i++) host.getTributeZone().add(card("v" + i, "Violeta " + i, 1, 1, "P"));
        assertThat(Chain.activateByPlayer(room, "host", "pl", "plaga-rabiosa:turno-aliado-en-tributos")).isNotNull();
    }

    @Test
    void caballeroOlvidadoTakesUpToTwoVioletsOfFiveWhenSummoned() {
        host.getDeck().addAll(0, List.of(card("p1", "Violeta 1", 1, 1, "P"), card("r1", "Roja 1", 1, 1, "R"), card("p2", "Violeta 2", 1, 1, "P"),
                card("r2", "Roja 2", 1, 1, "R"), card("r3", "Roja 3", 1, 1, "R")));
        inHand("x", "Pago", 1, 1, "R");
        summon(violet("co", "Caballero Olvidado", 1, 1, 2));
        Prompt take = room.getPrompt();
        assertThat(take.getValidIds()).containsExactlyInAnyOrder("p1", "p2");
        answer(take, "p1", "p2");
        answer("x");
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("p1", "p2").doesNotContain("x");
    }

    @Test
    void sombraAndanteDestroyedInAClashDestroysAnEnemyOfLevelTwoOrLess() {
        room.setFirstTurn(false);
        CardSlot sombra = onField(host, violet("sa", "Sombra Andante", 2, 2, 4), FRONT);
        CardSlot big = onField(guest, enemy("big", 4, 10), FRONT);
        big.setTapped(true);
        CardSlot small = onField(guest, enemy("sm", 2, 3), FRONT);
        onField(guest, enemy("l3", 3, 3), FRONT).setTapped(true);
        assertThat(CombatRules.attackByPlayer(room, "host", "sa", "big")).isNull();
        for (int i = 0; i < 20 && host.getField().contains(sombra); i++) {
            Prompt p = room.getPrompt();
            if (p == null) Chain.advance(room);
            else answer(p);
        }
        // Mandatory: the target is asked right away
        Prompt target = room.getPrompt();
        assertThat(target.getValidIds()).containsExactly("sm");
        answer(target, "sm");
        assertThat(guest.getField()).doesNotContain(small).contains(big);
    }

    @Test
    void guardiaDelSepulcroDestroysAnEnemyOfLevelTwoOrLessWhenItAttacks() {
        onField(host, violet("gs", "Guardia Del Sepulcro", 3, 3, 8), FRONT);
        CardSlot small = onField(guest, enemy("sm", 2, 3), FRONT);
        onField(guest, enemy("l3", 3, 3), FRONT);
        assertThat(CombatRules.attackByPlayer(room, "host", "gs", null)).isNull();
        Prompt target = room.getPrompt();
        assertThat(target.getValidIds()).containsExactly("sm");
        answer(target, "sm");
        assertThat(guest.getField()).doesNotContain(small);
    }

    @Test
    void hematofagoMaySummonAVioletFromTheTributesForOneLess() {
        CardSlot hema = onField(host, violet("hi", "Hematófago Invernal", 5, 4, 10), FRONT);
        CardSlot dead = violet("dv", "Violeta Tributada", 2, 2, 4);
        host.getTributeZone().addAll(List.of(dead, card("rt", "Roja", 1, 1, "R")));
        int before = gems();
        assertThat(CombatRules.attackByPlayer(room, "host", "hi", null)).isNull();
        accept(hema);
        Prompt pick = room.getPrompt();
        assertThat(pick.getValidIds()).containsExactly("dv");
        answer(pick, "dv");
        assertThat(host.getField()).contains(dead);
        assertThat(gems()).isEqualTo(before - 1);
    }

    @Test
    void muerteCaminanteReactivatesOncePerTurnWhenACreatureIsDestroyed() {
        CardSlot muerte = onField(host, violet("mc", "Muerte Caminante", 7, 5, 14), FRONT);
        muerte.setTapped(true);
        onField(guest, enemy("a", 2, 3), FRONT);
        onField(guest, enemy("b", 2, 3), FRONT);
        play(utility("s1", "Subyugar", 5, "P"));
        answer("a");
        drain();
        assertThat(muerte.isTapped()).isFalse();
        muerte.setTapped(true);
        host.setResourceCount(20);
        room.setSharedCounter(-20);
        play(utility("s2", "Subyugar", 5, "P"));
        answer("b");
        drain();
        assertThat(muerte.isTapped()).isTrue();
    }

    @Test
    void subyugarDestroysAnEnemyOfLevelFiveOrLess() {
        CardSlot five = onField(guest, enemy("l5", 5, 12), FRONT);
        onField(guest, enemy("l6", 6, 14), FRONT);
        play(utility("sy", "Subyugar", 5, "P"));
        Prompt target = room.getPrompt();
        assertThat(target.getValidIds()).containsExactly("l5");
        answer(target, "l5");
        assertThat(guest.getField()).doesNotContain(five);
    }

    @Test
    void liberacionDestroysALevelOneAndThenMaySummonAVioletFromTheTributes() {
        CardSlot one = onField(guest, enemy("l1", 1, 3), FRONT);
        onField(guest, enemy("l2", 2, 3), FRONT);
        CardSlot dead = violet("dv", "Violeta Tributada", 2, 2, 4);
        host.getTributeZone().add(dead);
        play(utility("li", "Liberación de las Almas Impuras", 1, "P"));
        Prompt target = room.getPrompt();
        assertThat(target.getValidIds()).containsExactly("l1");
        answer(target, "l1");
        assertThat(guest.getField()).doesNotContain(one);
        // "puedes invocar": yes (the card itself), then the Violet
        answer("li");
        assertThat(room.getPrompt().getValidIds()).containsExactly("dv");
        answer("dv");
        assertThat(host.getField()).contains(dead);
    }

    @Test
    void convocacionTakesOneVioletAndStaysExhausted() {
        host.getDeck().addAll(0, List.of(card("p1", "Violeta 1", 1, 1, "P"), card("r1", "Roja 1", 1, 1, "R"), card("r2", "Roja 2", 1, 1, "R"),
                card("r3", "Roja 3", 1, 1, "R"), card("r4", "Roja 4", 1, 1, "R")));
        CardSlot conv = utility("cv", "Convocación", 1, "P");
        play(conv);
        Prompt take = room.getPrompt();
        assertThat(take.getValidIds()).containsExactly("p1");
        answer(take, "p1");
        assertThat(conv.isTapped()).isTrue();
        assertThat(host.getField()).contains(conv);
    }
}
