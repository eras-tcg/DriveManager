package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
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
        assertThat(Summons.canSpecial(room, "host", evolved)).isTrue();
        assertThat(Summons.specialByPlayer(room, "host", "evo", "fl")).isNull();
        Prompt pay = room.getPrompt();
        assertThat(pay.getValidIds()).hasSize(5);
        assertThat(Answers.answer(room, "host", pay.getId(), List.of(), List.of())).contains("elige 5");
        assertThat(Answers.answer(room, "host", pay.getId(), pay.getValidIds(), List.of())).isNull();

        assertThat(host.getField()).contains(evolved);
        assertThat(host.getTributeZone()).isEmpty();
        assertThat(EffectEngine.gemsOf(room, "host")).isZero();
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
}
