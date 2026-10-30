package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.EffectScript.Limit;
import com.drive.drive_manager.simulator.engine.EffectScript.Moment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Designer rulings (2026-10-30): a utility can be played only if its "En Juego" can be activated (limit, condition,
 * cost, targets), and no Definitive effect — of any card or moment — can be activated on the first turn of the game.
 */
class UtilityActivationTest {

    private GameRoom room;
    private PlayerState host;

    @BeforeEach
    void setUp() {
        TestTable table = TestTable.inProgress(5, 6);
        room = table.room();
        host = table.host();
    }

    @Test
    void nexoCannotBePlayedWithoutAnAllyToChoose() {
        CardSlot nexo = utility("nx", "Nexo Del Inframundo Material");
        host.getHand().add(nexo);

        String error = Summons.playByPlayer(room, "host", "nx", 50.0, BACK);

        assertThat(error).contains("no se puede jugar").contains("coste");
        assertThat(host.getHand()).contains(nexo);
        assertThat(host.getField()).isEmpty();
        assertThat(host.getDiscardPile()).isEmpty();
        assertThat(host.getResourceCount()).isEqualTo(5);
    }

    @Test
    void nexoCanBePlayedOnceThereIsAnAlly() {
        onField(host, card("al", "Aliado", 1, 1, "R"), FRONT);
        host.getHand().add(utility("nx", "Nexo Del Inframundo Material"));

        assertThat(Summons.playByPlayer(room, "host", "nx", 50.0, BACK)).isNull();
        assertThat(room.getPrompt()).isNotNull();
    }

    @Test
    void aUtilityWhoseConditionDoesNotHoldCannotBePlayed() {
        // Última Línea De Defensa: "si tienes 2 o menos vidas bocabajo"
        for (int i = 0; i < 3; i++) {
            CardSlot life = card("hl" + i, "Vida", 1, 1, "R");
            life.setFaceDown(true);
            host.getLifeStack().add(life);
        }
        host.getHand().add(utility("ul", "Última Línea De Defensa"));

        assertThat(Summons.playByPlayer(room, "host", "ul", 50.0, BACK)).contains("condición");
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("ul");
    }

    @Test
    void noDefinitiveUtilityOnTheFirstTurnOfTheGame() {
        room.setFirstTurn(true);
        host.getHand().add(utility("hp", "Hechizo Premonitorio"));

        assertThat(Summons.playByPlayer(room, "host", "hp", 50.0, BACK)).contains("primer turno");
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("hp");

        room.setFirstTurn(false);
        assertThat(Summons.playByPlayer(room, "host", "hp", 50.0, BACK)).isNull();
    }

    @Test
    void aSecondDefinitiveUtilityInTheSameTurnCannotBePlayed() {
        host.getHand().add(utility("hp1", "Hechizo Premonitorio"));
        host.getHand().add(utility("hp2", "Hechizo Premonitorio"));
        assertThat(Summons.playByPlayer(room, "host", "hp1", 50.0, BACK)).isNull();
        while (room.getPrompt() != null) Answers.answer(room, "host", room.getPrompt().getId(), List.of(), List.of());

        assertThat(Summons.playByPlayer(room, "host", "hp2", 50.0, BACK)).contains("ya activaste un Efecto Definitivo");
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("hp2");
    }

    @Test
    void theBoardIsToldWhichUtilitiesCannotBePlayed() {
        CardSlot nexo = utility("nx", "Nexo Del Inframundo Material");
        host.getHand().addAll(List.of(nexo, utility("hp", "Hechizo Premonitorio"), card("cr", "Criatura", 1, 1, "R")));

        assertThat(Summons.unplayable(room, "host")).containsExactly("nx");
        assertThat(host.getHand()).first().isSameAs(nexo);

        onField(host, card("al", "Aliado", 1, 1, "R"), FRONT);
        assertThat(Summons.unplayable(room, "host")).isEmpty();
    }

    @Test
    void theFirstTurnStopsDefinitiveEffectsOfAnyMoment() {
        CardSlot creature = onField(host, card("cr", "Criatura", 1, 1, "R"), FRONT);
        EffectScript activated = new EffectScript("prueba:turno-aliado", Moment.ACTIVATED, false, "Turno Aliado",
                "Efecto Definitivo. Roba 1 carta.", List.of(new EffectStep.Draw(1))).limited(Limit.DEFINITIVE);
        PendingEffect effect = Chain.candidate(activated, "host", creature, 0);

        room.setFirstTurn(true);
        assertThat(Chain.limitReached(room, effect, activated)).isTrue();
        assertThat(Chain.limitText(room, effect, activated)).contains("primer turno");

        room.setFirstTurn(false);
        assertThat(Chain.limitReached(room, effect, activated)).isFalse();
    }
}
