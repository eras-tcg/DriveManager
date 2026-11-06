package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Reactivo-Modificadores of a cost: they lower a special summon's cost, some for a payment of their own. */
class CostModifierTest {

    private GameRoom room;
    private PlayerState host;
    private PlayerState guest;

    @BeforeEach
    void setUp() {
        TestTable table = TestTable.inProgress(5, 6);
        room = table.room();
        host = table.host();
        guest = table.guest();
    }

    @Test
    void flambreikMayPayFiveDragonsToReduceTheEvolutionCost() {
        room.setSharedCounter(-1);
        onField(host, card("fl", "Flambreik", 2, 1, "G")).setClasses(new ArrayList<>(List.of("dragón")));
        for (int i = 0; i < 5; i++) {
            CardSlot dragon = card("t" + i, "Dragón " + i, 1, 1, "G");
            dragon.setClasses(new ArrayList<>(List.of("dragón")));
            host.getTributeZone().add(dragon);
        }
        CardSlot evolved = card("evo", "Evolución", 3, 2, "G");
        evolved.setSpecialSummonKind("evolution");
        evolved.setSpecialCost(2);
        evolved.setRequirement("criatura nivel 1.");
        host.getHand().add(evolved);

        // 1 gem and a cost of 2: only with Flambreik's reduction
        assertThat(SpecialRequirements.canSpecial(room, "host", evolved)).isTrue();
        assertThat(SpecialSummons.specialByPlayer(room, "host", "evo", "fl")).isNull();
        Prompt pay = room.getPrompt();
        assertThat(pay.getValidIds()).hasSize(5);
        // Nothing else pays it: the payment is required, so the choice cannot be left empty
        assertThat(pay.getMin()).isEqualTo(5);
        assertThat(Answers.answer(room, "host", pay.getId(), List.of(), List.of())).contains("elegir 5");
        assertThat(Answers.answer(room, "host", pay.getId(), pay.getValidIds(), List.of())).isNull();

        assertThat(host.getField()).contains(evolved);
        assertThat(host.getTributeZone()).isEmpty();
        assertThat(EffectEngine.gemsOf(room, "host")).isZero();
    }

    @Test
    void flambreikPaymentStaysOptionalWithGemsToPayTheFullCost() {
        room.setSharedCounter(-3);
        onField(host, card("fl", "Flambreik", 2, 1, "G")).setClasses(new ArrayList<>(List.of("dragón")));
        for (int i = 0; i < 5; i++) {
            CardSlot dragon = card("t" + i, "Dragón " + i, 1, 1, "G");
            dragon.setClasses(new ArrayList<>(List.of("dragón")));
            host.getTributeZone().add(dragon);
        }
        CardSlot evolved = card("evo", "Evolución", 3, 2, "G");
        evolved.setSpecialSummonKind("evolution");
        evolved.setSpecialCost(2);
        evolved.setRequirement("criatura nivel 1.");
        host.getHand().add(evolved);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "evo", "fl")).isNull();
        Prompt pay = room.getPrompt();
        assertThat(pay.getMin()).isZero();
        assertThat(Answers.answer(room, "host", pay.getId(), List.of(), List.of())).isNull();
        assertThat(host.getField()).contains(evolved);
        assertThat(host.getTributeZone()).hasSize(5);
    }

    @Test
    void criaDeDragonRunicoMayPayFiveDragonsToReduceItsEvolutionCostOnAnyTurn() {
        // "Todos Los Turnos: cuando esta carta fuera a invocar por Evolución…": also on the opponent's turn
        room.setCurrentTurnUserId("guest");
        room.setSharedCounter(-1);
        onField(host, card("cr", "Cría De Dragón Rúnico", 1, 1, "G")).setClasses(new ArrayList<>(List.of("dragón")));
        for (int i = 0; i < 5; i++) {
            CardSlot dragon = card("t" + i, "Dragón " + i, 1, 1, "G");
            dragon.setClasses(new ArrayList<>(List.of("dragón")));
            host.getTributeZone().add(dragon);
        }
        CardSlot evolved = card("evo", "Evolución", 3, 2, "G");
        evolved.setSpecialSummonKind("evolution");
        evolved.setSpecialCost(2);
        evolved.setRequirement("criatura nivel 1.");
        host.getHand().add(evolved);

        assertThat(CostModifiers.available(room, host, CostModifiers.Event.SPECIAL_SUMMON, "evolution", evolved,
                host.getField().get(0))).isEqualTo(1);
    }

    @Test
    void criaDeDragonRunicoSendsOtherFiveNotTheCardBeingSummoned() {
        onField(host, card("cr", "Cría De Dragón Rúnico", 1, 1, "G")).setClasses(new ArrayList<>(List.of("dragón")));
        for (int i = 0; i < 4; i++) {
            CardSlot dragon = card("t" + i, "Dragón " + i, 1, 1, "G");
            dragon.setClasses(new ArrayList<>(List.of("dragón")));
            host.getTributeZone().add(dragon);
        }
        // The evolution is a [dragón] in the Tributos too: it does not count among the 5
        CardSlot evolved = card("evo", "Evolución", 3, 2, "G");
        evolved.setClasses(new ArrayList<>(List.of("dragón")));
        evolved.setSpecialSummonKind("evolution");
        evolved.setSpecialCost(2);
        evolved.setRequirement("criatura nivel 1.");
        host.getTributeZone().add(evolved);

        assertThat(CostModifiers.available(room, host, CostModifiers.Event.SPECIAL_SUMMON, "evolution", evolved,
                host.getField().get(0))).isZero();
    }

    @Test
    void duregonOnlyLowersTheEvolutionCostOnItsControllersTurn() {
        // "Turno Aliado": on the opponent's turn the summon costs it all
        for (int i = 0; i < 5; i++) {
            CardSlot dragon = card("t" + i, "Dragón " + i, 1, 1, "G");
            dragon.setClasses(new ArrayList<>(List.of("dragón")));
            host.getTributeZone().add(dragon);
        }
        CardSlot duregon = onField(host, card("dur", "Duregón", 1, 1, "G"));
        CardSlot evolved = card("evo", "Evolución", 3, 2, "G");
        evolved.setSpecialSummonKind("evolution");
        evolved.setSpecialCost(2);
        evolved.setRequirement("criatura nivel 1.");

        assertThat(CostModifiers.available(room, host, CostModifiers.Event.SPECIAL_SUMMON, "evolution", evolved, duregon)).isEqualTo(1);
        room.setCurrentTurnUserId("guest");
        assertThat(CostModifiers.available(room, host, CostModifiers.Event.SPECIAL_SUMMON, "evolution", evolved, duregon)).isZero();
    }

    @Test
    void anEvolutionIsOfferedOnlyWithASummonerItCanBePaidWith() {
        // 1 gem and a cost of 2: only Duregón's discount pays it, and that discount is only for Duregón as the summoner
        room.setSharedCounter(-1);
        for (int i = 0; i < 5; i++) {
            CardSlot dragon = card("t" + i, "Dragón " + i, 1, 1, "G");
            dragon.setClasses(new ArrayList<>(List.of("dragón")));
            host.getTributeZone().add(dragon);
        }
        onField(host, card("dur", "Duregón", 1, 1, "G"));
        onField(host, card("gal", "Galveta", 2, 2, "G"));
        CardSlot evolved = card("evo", "Evolución", 3, 3, "G");
        evolved.setSpecialSummonKind("evolution");
        evolved.setSpecialCost(2);
        host.getHand().add(evolved);

        // Level 2: only Galveta can be the summoner, and without Duregón's discount it cannot be paid. It used to be
        // offered anyway, and choosing Galveta was refused ("no tienes gemas suficientes"), again and again
        evolved.setRequirement("criatura nivel 2.");
        assertThat(SpecialRequirements.canSpecial(room, "host", evolved)).isFalse();
        assertThat(SpecialSummons.specialByPlayer(room, "host", "evo", null)).contains("gemas suficientes");
        assertThat(room.getPrompt()).isNull();

        // Level 1: Duregón can be the summoner, and with its discount it is paid; it is the only one offered
        evolved.setRequirement("criatura nivel 1.");
        assertThat(SpecialRequirements.canSpecial(room, "host", evolved)).isTrue();
        assertThat(SpecialSummons.specialByPlayer(room, "host", "evo", null)).isNull();
        assertThat(room.getPrompt().getValidIds()).containsExactly("dur");
    }
}
