package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Opcionales en Auto": cost reducers are asked only while the cost can still go down, and the "En Juego" of a card just
 * played goes into the chain without the confirmation when nothing else triggered. On keeps asking both.
 */
class OptionalAutoTest {

    private GameRoom room;
    private PlayerState host;
    private PlayerState guest;

    @BeforeEach
    void setUp() {
        TestTable table = TestTable.inProgress(5, 8);
        room = table.room();
        host = table.host();
        guest = table.guest();
    }

    /** Two Bosque Frondoso and an evolution of special cost 1. */
    private CardSlot twoForestsAndAnEvolution() {
        room.setSharedCounter(-3);
        for (String id : List.of("b1", "b2")) {
            CardSlot bosque = onField(host, card(id, "Bosque Frondoso", 1, 2, "G"), BACK);
            bosque.setCardType("structure");
        }
        onField(host, card("base", "Base", 1, 1, "G"), FRONT);
        CardSlot evolved = card("evo", "Evolución", 2, 2, "G");
        evolved.setSpecialSummonKind("evolution");
        evolved.setSpecialCost(1);
        evolved.setRequirement("criatura nivel 1.");
        host.getHand().add(evolved);
        return evolved;
    }

    private long tappedForests() {
        return host.getField().stream().filter(c -> c.getInstanceId().startsWith("b") && c.isTapped()).count();
    }

    @Test
    void withAutoTheForestsStopBeingAskedOnceTheCostIsZero() {
        host.setAutoOptional(true);
        CardSlot evolved = twoForestsAndAnEvolution();
        assertThat(SpecialSummons.specialByPlayer(room, "host", "evo", "base")).isNull();
        Prompt ask = room.getPrompt();
        assertThat(ask.getPurpose()).isEqualTo("COST_TAP");
        assertThat(Answers.answer(room, "host", ask.getId(), List.of(ask.getValidIds().get(0)), List.of())).isNull();
        // The cost is 0 now: the other forest is not asked
        assertThat(room.getPrompt() == null || !"COST_TAP".equals(room.getPrompt().getPurpose())).isTrue();
        assertThat(host.getField()).contains(evolved);
        assertThat(tappedForests()).isEqualTo(1);
    }

    @Test
    void withOnEveryForestIsAskedEvenWhenTheCostIsAlreadyZero() {
        twoForestsAndAnEvolution();
        assertThat(SpecialSummons.specialByPlayer(room, "host", "evo", "base")).isNull();
        Prompt first = room.getPrompt();
        Answers.answer(room, "host", first.getId(), List.of(first.getValidIds().get(0)), List.of());
        Prompt second = room.getPrompt();
        assertThat(second.getPurpose()).isEqualTo("COST_TAP");
        assertThat(second.getValidIds()).doesNotContain(first.getValidIds().get(0));
    }

    @Test
    void withAutoAForestIsNotAskedForACostOfZero() {
        host.setAutoOptional(true);
        CardSlot evolved = twoForestsAndAnEvolution();
        evolved.setSpecialCost(0);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "evo", "base")).isNull();
        assertThat(room.getPrompt() == null || !"COST_TAP".equals(room.getPrompt().getPurpose())).isTrue();
        assertThat(host.getField()).contains(evolved);
        assertThat(tappedForests()).isZero();
    }

    @Test
    void withAutoTheForestIsStillAskedWhileItLowersTheCost() {
        host.setAutoOptional(true);
        CardSlot evolved = twoForestsAndAnEvolution();
        evolved.setSpecialCost(2);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "evo", "base")).isNull();
        Prompt first = room.getPrompt();
        Answers.answer(room, "host", first.getId(), List.of(first.getValidIds().get(0)), List.of());
        // 1 left: the second one is asked too
        assertThat(room.getPrompt().getPurpose()).isEqualTo("COST_TAP");
        Answers.answer(room, "host", room.getPrompt().getId(), List.of(room.getPrompt().getValidIds().get(0)), List.of());
        assertThat(host.getField()).contains(evolved);
        assertThat(tappedForests()).isEqualTo(2);
    }

    private CardSlot fuegoAbrasador() {
        room.setConfirmMandatory(true);
        onField(guest, card("enemy", "Enemigo", 1, 1, "R"), FRONT);
        CardSlot fuego = card("fuego", "Fuego Abrasador", 1, 2, "G");
        fuego.setCardType("utility");
        host.getHand().add(fuego);
        return fuego;
    }

    @Test
    void withAutoALoneEnJuegoFromTheHandIsChainedWithoutConfirming() {
        host.setAutoOptional(true);
        fuegoAbrasador();
        assertThat(Summons.playByPlayer(room, "host", "fuego", null, null)).isNull();
        // Straight to its choice: the enemy to destroy
        Prompt choice = room.getPrompt();
        assertThat(choice.getPurpose()).isNotEqualTo("ORDER");
        assertThat(choice.getValidIds()).contains("enemy");
    }

    @Test
    void withAutoALoneEnJuegoPlayedOtherwiseIsChainedWithoutConfirming() {
        // As a life or an effect plays it: Summons.play, then the chain goes on
        host.setAutoOptional(true);
        CardSlot fuego = fuegoAbrasador();
        Summons.play(room, host, fuego, 0, null, null, null);
        Chain.advance(room);
        Prompt choice = room.getPrompt();
        assertThat(choice.getPurpose()).isNotEqualTo("ORDER");
        assertThat(choice.getValidIds()).contains("enemy");
    }

    @Test
    void withOnALoneEnJuegoIsConfirmedFirst() {
        fuegoAbrasador();
        assertThat(Summons.playByPlayer(room, "host", "fuego", null, null)).isNull();
        Prompt confirm = room.getPrompt();
        assertThat(confirm.getPurpose()).isEqualTo("ORDER");
        assertThat(confirm.getValidIds()).containsExactly("fuego");
    }
}
