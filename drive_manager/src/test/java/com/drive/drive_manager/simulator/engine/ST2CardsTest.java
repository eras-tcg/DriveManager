package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** ST2 (Verde, estructura nueva del 2026-11-15): one test (or more) per programmed effect, from the printed text. */
class ST2CardsTest extends CardBatchSupport {

    private static CardSlot green(String id, String name, int cost, int level, int strength) {
        CardSlot c = card(id, name, cost, level, "G");
        c.setStrength(strength);
        return c;
    }

    private static CardSlot enemy(String id, int strength) {
        CardSlot c = card(id, "Enemigo " + id, 1, 1, "R");
        c.setStrength(strength);
        return c;
    }

    // ------------------------------------------------------------------ Criaturas

    @Test
    void beaTakesUpToTwoGreensOfFiveSendsTheRestToTheBottomThenTributesOneFromTheHand() {
        List<CardSlot> top = List.of(card("g1", "Verde 1", 1, 1, "G"), card("r1", "Roja 1", 1, 1, "R"), card("g2", "Verde 2", 1, 1, "G"),
                card("r2", "Roja 2", 1, 1, "R"), card("g3", "Verde 3", 1, 1, "G"));
        host.getDeck().addAll(0, top);
        CardSlot pay = inHand("x", "Pago", 1, 1, "R");
        summon(green("be", "Bea", 1, 1, 2));
        Prompt take = room.getPrompt();
        assertThat(take.getValidIds()).containsExactlyInAnyOrder("g1", "g2", "g3");
        answer(take, "g1", "g2");
        answer("x");
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("g1", "g2").doesNotContain("x");
        assertThat(host.getTributeZone()).contains(pay);
        assertThat(host.getDeck().subList(host.getDeck().size() - 3, host.getDeck().size()))
                .extracting(CardSlot::getInstanceId).containsExactlyInAnyOrder("r1", "r2", "g3");
        assertThat(room.getChain()).isEmpty();
    }

    @Test
    void soldadoAguijonTributesThreeFromTheDeckTopThenTakesOneFromTheTributes() {
        host.getDeck().addAll(0, List.of(card("d1", "Uno", 1, 1, "G"), card("d2", "Dos", 1, 1, "G"), card("d3", "Tres", 1, 1, "G")));
        CardSlot old = card("old", "Antigua", 1, 1, "R");
        host.getTributeZone().add(old);
        summon(green("sa", "Soldado Aguijón", 2, 2, 4));
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).contains("d1", "d2", "d3", "old");
        Prompt take = room.getPrompt();
        assertThat(take.getValidIds()).contains("d1", "d2", "d3", "old");
        answer(take, "old");
        assertThat(host.getHand()).contains(old);
        assertThat(host.getTributeZone()).hasSize(3);
        assertThat(room.getChain()).isEmpty();
    }

    @Test
    void drakoakDestroysAnEnemyOfStrengthSixOrLessWhenItAttacks() {
        CardSlot drakoak = onField(host, green("dk", "Drakoak", 3, 3, 8), FRONT);
        CardSlot weak = onField(guest, enemy("e6", 6), FRONT);
        onField(guest, enemy("e7", 7), FRONT);
        assertThat(CombatRules.attackByPlayer(room, "host", drakoak.getInstanceId(), null)).isNull();
        Prompt target = room.getPrompt();
        assertThat(target.getValidIds()).containsExactly("e6");
        answer(target, "e6");
        assertThat(guest.getField()).doesNotContain(weak);
    }

    @Test
    void dragofructisGivesItsEvolutionTheDestroyWhenItAttacks() {
        CardSlot holder = onField(host, green("ho", "Portador", 4, 3, 8), FRONT);
        holder.getResources().add(green("df", "Dragofructis", 2, 2, 5));
        CardSlot weak = onField(guest, enemy("e5", 5), FRONT);
        onField(guest, enemy("e9", 9), FRONT);
        assertThat(CombatRules.attackByPlayer(room, "host", "ho", null)).isNull();
        Prompt target = room.getPrompt();
        assertThat(target.getValidIds()).containsExactly("e5");
        answer(target, "e5");
        assertThat(guest.getField()).doesNotContain(weak);
        assertThat(room.getTimeline()).filteredOn(e -> GameEvent.EFFECT_ACTIVATED.equals(e.getType())).isNotEmpty();
    }

    @Test
    void dragonVenenosoGivesItsEvolutionUpToThreeDestroysOfStrengthFiveOrLess() {
        CardSlot holder = onField(host, green("ho", "Portador", 7, 5, 13), FRONT);
        holder.getResources().add(green("dv", "Dragón Venenoso", 4, 4, 11));
        CardSlot a = onField(guest, enemy("a", 5), FRONT);
        CardSlot b = onField(guest, enemy("b", 3), FRONT);
        CardSlot strong = onField(guest, enemy("c", 6), FRONT);
        assertThat(CombatRules.attackByPlayer(room, "host", "ho", null)).isNull();
        Prompt target = room.getPrompt();
        assertThat(target.getValidIds()).containsExactlyInAnyOrder("a", "b");
        answer(target, "a", "b");
        assertThat(guest.getField()).doesNotContain(a, b).contains(strong);
    }

    @Test
    void klausReactivatesItselfOncePerTurnWhenItAttacks() {
        room.setFirstTurn(false);
        CardSlot klaus = onField(host, green("kl", "Klaus", 4, 4, 11), FRONT);
        assertThat(CombatRules.attackByPlayer(room, "host", "kl", null)).isNull();
        drain();
        assertThat(klaus.isTapped()).isFalse();
        assertThat(CombatRules.attackByPlayer(room, "host", "kl", null)).isNull();
        drain();
        assertThat(klaus.isTapped()).isTrue();
    }

    @Test
    void centauroAntiguoMaySummonAGreenFromTheHandForTwoLess() {
        CardSlot centauro = onField(host, green("ct", "Centauro Antiguo", 7, 5, 13), FRONT);
        CardSlot greenInHand = green("gh", "Verde De Mano", 4, 4, 11);
        host.getHand().add(greenInHand);
        inHand("rh", "Roja De Mano", 1, 1, "R");
        int before = gems();
        assertThat(CombatRules.attackByPlayer(room, "host", "ct", null)).isNull();
        accept(centauro);
        Prompt pick = room.getPrompt();
        assertThat(pick.getValidIds()).containsExactly("gh");
        answer(pick, "gh");
        assertThat(host.getField()).contains(greenInHand);
        assertThat(gems()).isEqualTo(before - 2);
    }

    @Test
    void centauroAntiguoIsNotOfferedWithoutAGreenCreatureInTheHand() {
        onField(host, green("ct", "Centauro Antiguo", 7, 5, 13), FRONT);
        inHand("rh", "Roja De Mano", 1, 1, "R");
        assertThat(CombatRules.attackByPlayer(room, "host", "ct", null)).isNull();
        Prompt p = room.getPrompt();
        assertThat(p == null || !p.getValidIds().contains("ct")).isTrue();
    }

    // ------------------------------------------------------------------ Utilidades

    @Test
    void lanzamientoDeAguijonGivesAGreenAllyPerforanteUntilTheEndOfTheTurn() {
        CardSlot ally = onField(host, green("al", "Aliado Verde", 2, 2, 4), FRONT);
        onField(host, card("ro", "Aliado Rojo", 1, 1, "R"), FRONT);
        play(utility("la", "Lanzamiento de Aguijón", 1, "G"));
        Prompt target = room.getPrompt();
        assertThat(target.getValidIds()).containsExactly("al");
        answer(target, "al");
        assertThat(EffectEngine.keywords(ally)).contains("Perforante");
        assertThat(room.getChain()).isEmpty();
    }

    @Test
    void fuegoAbrasadorDestroysAnEnemyOfStrengthFourteenOrLess() {
        CardSlot weak = onField(guest, enemy("e14", 14), FRONT);
        onField(guest, enemy("e15", 15), FRONT);
        play(utility("fa", "Fuego Abrasador", 5, "G"));
        Prompt target = room.getPrompt();
        assertThat(target.getValidIds()).containsExactly("e14");
        answer(target, "e14");
        assertThat(guest.getField()).doesNotContain(weak);
    }
}
