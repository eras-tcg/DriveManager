package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** The board offers what the rules would accept. */
class LegalActionsTest {

    private GameRoom room;
    private PlayerState host;
    private PlayerState guest;

    @BeforeEach
    void setUp() {
        TestTable table = TestTable.inProgress(5, 6);
        room = table.room();
        host = table.host();
        guest = room.getPlayer("guest");
    }

    @Test
    void aCreatureInTheHandThatCanBePaidIsSummoned() {
        host.getHand().add(card("cheap", "Barata", 1, 1, "R"));
        host.getHand().add(card("dear", "Cara", 9, 9, "R"));

        var legal = LegalActions.of(room, "host");

        assertThat(legal.get("cheap")).containsExactly("summon");
        assertThat(legal).doesNotContainKey("dear");
    }

    @Test
    void aUtilityWithAnOnPlayEffectInTheHandDoesNotBreakTheList() {
        // Checking its "En Juego" moves it out of the hand and back while the hand is being read
        host.getHand().add(utility("hp", "Hechizo Premonitorio"));
        host.getHand().add(card("c", "Criatura", 1, 1, "R"));

        var legal = LegalActions.of(room, "host");

        assertThat(legal.get("c")).containsExactly("summon");
        assertThat(host.getHand()).extracting(c -> c.getInstanceId()).containsExactly("hp", "c");
    }

    @Test
    void onlyAnActiveCreatureOfTheFrontLineAttacks() {
        onField(host, card("front", "Frontal", 1, 1, "R"), FRONT);
        onField(host, card("back", "Trasera", 1, 1, "R"), BACK);
        onField(host, card("tired", "Agotada", 1, 1, "R"), FRONT).setTapped(true);

        var legal = LegalActions.of(room, "host");

        assertThat(legal.get("front")).containsExactly("attack");
        assertThat(legal).doesNotContainKeys("back", "tired");
    }

    @Test
    void theOpponentHasNoAttackOnYourTurn() {
        onField(guest, card("g", "Rival", 1, 1, "R"), FRONT);

        assertThat(LegalActions.of(room, "guest")).doesNotContainKey("g");
    }

    @Test
    void attackTargetsAreExhaustedEnemiesOfTheFrontLine() {
        onField(host, card("a", "Atacante", 1, 1, "R"), FRONT);
        onField(guest, card("t", "Agotado", 1, 1, "R"), FRONT).setTapped(true);
        onField(guest, card("f", "Activo", 1, 1, "R"), FRONT);

        assertThat(LegalActions.attackTargets(room, "host").get("a")).containsExactly("t");
    }

    @Test
    void whatTheBoardOffersIsWhatTheActionAccepts() {
        host.getHand().add(card("c", "Criatura", 1, 1, "R"));

        assertThat(LegalActions.of(room, "host").get("c")).contains("summon");
        assertThat(Summons.normalSummon(room, "host", "c", 50.0, BACK)).isNull();
    }

    @Test
    void theBoardReceivesThem() {
        host.getHand().add(card("c", "Criatura", 1, 1, "R"));

        var update = com.drive.drive_manager.simulator.GameStateUpdate.from(room, "TEST", "host");

        assertThat(update.getLegalActions().get("host").get("c")).containsExactly("summon");
        assertThat(update.getAttackTargets()).containsKeys("host", "guest");
    }
}
