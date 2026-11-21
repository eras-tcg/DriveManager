package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** ST4 (Rojo, Ascenso; estructura nueva del 2026-11-15): one test (or more) per programmed effect, from the printed text. */
@Tag("cards")
@Tag("st4")
class ST4CardsTest extends CardBatchSupport {

    private static CardSlot red(String id, String name, int cost, int level, int strength) {
        CardSlot c = card(id, name, cost, level, "R");
        c.setStrength(strength);
        return c;
    }

    private static CardSlot enemy(String id, int strength) {
        CardSlot c = card(id, "Enemigo " + id, 1, 1, "G");
        c.setStrength(strength);
        return c;
    }

    @Test
    void everyAscensoHasItsRequirementProgrammed() {
        for (String name : List.of("Sable de Plata", "Bafón", "Flinc", "Dante", "Leorio", "Boltz", "Campeón de los Espadones Dorados",
                "Espadón Hábil", "Claus el Joven Comandante", "Valentine la Comandante Carismática")) {
            CardSlot c = new CardSlot();
            c.setCardName(name);
            assertThat(CardEffects.requirementOf(c)).as(name).isNotNull();
        }
    }

    @Test
    void sableDePlataAscendsOnlyWhileYouControlTwoOrMoreAllies() {
        CardSlot sable = red("sp", "Sable de Plata", 2, 2, 6);
        sable.setSpecialSummonKind("promotion");
        sable.setSpecialCost(1);
        host.getHand().add(sable);
        onField(host, red("s", "Invocador", 1, 1, 3), FRONT);
        assertThat(SpecialRequirements.canSpecial(room, "host", sable)).isFalse();
        onField(host, red("o", "Otro", 3, 3, 8), BACK);
        assertThat(SpecialRequirements.canSpecial(room, "host", sable)).isTrue();
    }

    @Test
    void sableDePlataGainsThreeUntilTheEndOfTheAttack() {
        room.setFirstTurn(false);
        CardSlot sable = onField(host, red("sp", "Sable de Plata", 2, 2, 6), FRONT);
        // 6 + 3 beats 8 in the clash; after the attack it is 6 again
        CardSlot eight = onField(guest, enemy("e8", 8), FRONT);
        eight.setTapped(true);
        assertThat(CombatRules.attackByPlayer(room, "host", "sp", "e8")).isNull();
        drain();
        assertThat(guest.getField()).doesNotContain(eight);
        assertThat(host.getField()).contains(sable);
        assertThat(EffectEngine.strength(sable)).isEqualTo(6);
    }

    @Test
    void benzoSendsFiveRedTributesToTheBottomToSummonItselfFreeWhenAnAllyAttacks() {
        CardSlot benzo = red("bz", "Benzo", 1, 1, 4);
        host.getHand().add(benzo);
        for (int i = 0; i < 5; i++) host.getTributeZone().add(card("r" + i, "Roja " + i, 1, 1, "R"));
        onField(host, red("at", "Atacante", 2, 2, 5), FRONT);
        int before = gems();
        assertThat(CombatRules.attackByPlayer(room, "host", "at", null)).isNull();
        untilOffered(benzo);
        accept(benzo);
        answer("r0", "r1", "r2", "r3", "r4");
        assertThat(host.getField()).contains(benzo);
        assertThat(gems()).isEqualTo(before);
    }

    @Test
    void leorioGainsAGemWhenItDestroysAnEnemyInAClash() {
        room.setFirstTurn(false);
        onField(host, red("le", "Leorio", 3, 3, 9), FRONT);
        CardSlot weak = onField(guest, enemy("w", 4), FRONT);
        weak.setTapped(true);
        int before = gems();
        assertThat(CombatRules.attackByPlayer(room, "host", "le", "w")).isNull();
        drain();
        assertThat(guest.getField()).doesNotContain(weak);
        assertThat(gems()).isEqualTo(before + 1);
    }

    @Test
    void boltzReactivatesWhenItDestroysAnEnemyInAClash() {
        room.setFirstTurn(false);
        CardSlot boltz = onField(host, red("bo", "Boltz", 7, 5, 13), FRONT);
        CardSlot weak = onField(guest, enemy("w", 4), FRONT);
        weak.setTapped(true);
        assertThat(CombatRules.attackByPlayer(room, "host", "bo", "w")).isNull();
        drain();
        assertThat(guest.getField()).doesNotContain(weak);
        assertThat(boltz.isTapped()).isFalse();
    }

    @Test
    void valentineMaySummonARedFromTheHandInTheFrontLineForOneLessOncePerTurn() {
        room.setFirstTurn(false);
        CardSlot valentine = onField(host, red("va", "Valentine la Comandante Carismática", 5, 4, 11), FRONT);
        CardSlot redInHand = red("rh", "Roja De Mano", 3, 3, 8);
        host.getHand().add(redInHand);
        int before = gems();
        assertThat(CombatRules.attackByPlayer(room, "host", "va", null)).isNull();
        accept(valentine);
        answer("rh");
        assertThat(host.getField()).contains(redInHand);
        assertThat(EffectEngine.inFrontLine(redInHand)).isTrue();
        assertThat(gems()).isEqualTo(before - 2);
    }

    @Test
    void enfrentamientoDeGuerraGivesThreeAndTheAllyMayAttackAnEnemyWithoutExhausting() {
        room.setFirstTurn(false);
        CardSlot ally = onField(host, red("al", "Aliado", 2, 2, 5), FRONT);
        CardSlot target = onField(guest, enemy("w", 7), FRONT);
        target.setTapped(true);
        play(utility("eg", "Enfrentamiento De Guerra", 3, "R"));
        answer("al");
        assertThat(EffectEngine.strength(ally)).isEqualTo(8);
        // "puede atacar": yes
        answer("eg");
        Prompt attack = room.getPrompt();
        assertThat(attack.getMin()).isEqualTo(1);
        assertThat(attack.getValidIds()).containsExactly("w");
        answer(attack, "w");
        drain();
        assertThat(guest.getField()).doesNotContain(target);
        assertThat(ally.isTapped()).isFalse();
    }

    @Test
    void enfrentamientoDeGuerraGivesTheStrengthUntilTheEndOfTheTurn() {
        room.setFirstTurn(false);
        CardSlot ally = onField(host, red("al", "Aliado", 2, 2, 5), FRONT);
        play(utility("eg", "Enfrentamiento De Guerra", 3, "R"));
        answer("al");
        assertThat(EffectEngine.strength(ally)).isEqualTo(8);
        // "hasta el final del turno": back to its own strength once the turn changes
        drain();
        com.drive.drive_manager.simulator.actions.TurnActions.passTurn(room, "host");
        assertThat(EffectEngine.strength(ally)).isEqualTo(5);
    }

    @Test
    void enfrentamientoDeGuerraMayAttackAnActiveEnemyToo() {
        room.setFirstTurn(false);
        CardSlot ally = onField(host, red("al", "Aliado", 2, 2, 5), FRONT);
        CardSlot active = onField(guest, enemy("ac", 7), FRONT);
        play(utility("eg", "Enfrentamiento De Guerra", 3, "R"));
        answer("al");
        answer("eg");
        Prompt attack = room.getPrompt();
        assertThat(attack.getValidIds()).containsExactly("ac");
        answer(attack, "ac");
        drain();
        assertThat(guest.getField()).doesNotContain(active);
        assertThat(ally.isTapped()).isFalse();
    }

    @Test
    void enfrentamientoDeGuerraCannotAttackTheRivalDirectly() {
        room.setFirstTurn(false);
        CardSlot ally = onField(host, red("al", "Aliado", 2, 2, 5), FRONT);
        int lives = guest.getLifeStack().size();
        play(utility("eg", "Enfrentamiento De Guerra", 3, "R"));
        answer("al");
        if (room.getPrompt() != null && room.getPrompt().getValidIds().contains("eg")) answer("eg");
        drain();
        assertThat(room.getCombat()).isNull();
        assertThat(guest.getLifeStack()).hasSize(lives);
        assertThat(EffectEngine.strength(ally)).isEqualTo(8);
    }

    @Test
    void trampaDePisoLeavesNoBlockWhenARedAllyAttacksThatEnemy() {
        room.setFirstTurn(false);
        CardSlot exhausted = onField(guest, enemy("ex", 4), FRONT);
        exhausted.setTapped(true);
        onField(guest, enemy("bl", 20), FRONT);
        play(utility("tp", "Trampa de Piso", 1, "R"));
        Prompt target = room.getPrompt();
        assertThat(target.getValidIds()).containsExactly("ex");
        answer(target, "ex");
        onField(host, red("at", "Atacante Rojo", 3, 3, 9), FRONT);
        assertThat(CombatRules.attackByPlayer(room, "host", "at", "ex")).isNull();
        for (int i = 0; i < 20 && room.getCombat() != null; i++) {
            Prompt p = room.getPrompt();
            assertThat(p == null || !PromptPurpose.BLOCK.id().equals(p.getPurpose())).as("no se pide bloqueo").isTrue();
            if (p == null) Chain.advance(room);
            else answer(p);
        }
        // The attacked creature itself clashed and lost
        assertThat(guest.getField()).doesNotContain(exhausted);
    }

    @Test
    void trampaDePisoDoesNotStopABlockAgainstAnotherColor() {
        room.setFirstTurn(false);
        CardSlot exhausted = onField(guest, enemy("ex", 4), FRONT);
        exhausted.setTapped(true);
        onField(guest, enemy("bl", 20), FRONT);
        play(utility("tp", "Trampa de Piso", 1, "R"));
        answer("ex");
        CardSlot blue = card("at", "Atacante Azul", 3, 3, "B");
        blue.setStrength(9);
        onField(host, blue, FRONT);
        assertThat(CombatRules.attackByPlayer(room, "host", "at", "ex")).isNull();
        boolean asked = false;
        for (int i = 0; i < 20 && room.getCombat() != null; i++) {
            Prompt p = room.getPrompt();
            if (p != null && PromptPurpose.BLOCK.id().equals(p.getPurpose())) asked = true;
            if (p == null) Chain.advance(room);
            else answer(p);
        }
        assertThat(asked).isTrue();
    }

    @Test
    void espionajeTacticoTakesOneRedAndLaterSummonsARedForOneLess() {
        room.setFirstTurn(false);
        host.getDeck().addAll(0, List.of(card("r1", "Roja 1", 1, 1, "R"), card("g1", "Verde 1", 1, 1, "G"), card("g2", "Verde 2", 1, 1, "G"),
                card("g3", "Verde 3", 1, 1, "G"), card("g4", "Verde 4", 1, 1, "G")));
        CardSlot esp = utility("et", "Espionaje Táctico", 1, "R");
        play(esp);
        answer("r1");
        assertThat(esp.isTapped()).isTrue();
        esp.setTapped(false);
        CardSlot r1 = host.getHand().stream().filter(c -> "r1".equals(c.getInstanceId())).findFirst().orElseThrow();
        assertThat(Chain.activateByPlayer(room, "host", "et", "espionaje-tactico:turno-aliado")).isNull();
        answer("r1");
        assertThat(host.getField()).contains(r1);
        assertThat(host.getDiscardPile()).contains(esp);
    }
}
