package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;
import org.junit.jupiter.api.Test;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

class PlayabilityTest {

    @Test
    void aCreatureIsPlayableNowWithTheGemsOrAtItsNextTurnOrLater() {
        TestTable t = TestTable.inProgress(2, 8);
        GameRoom room = t.room();
        PlayerState host = t.host();
        host.setResourceCount(2);
        CardSlot two = card("c2", "Dos", 2, 2, "R"), three = card("c3", "Tres", 3, 2, "R"), seven = card("c7", "Siete", 7, 5, "R");
        host.getHand().add(two);
        host.getHand().add(three);
        host.getHand().add(seven);
        assertThat(Playability.level(room, "host", two)).isEqualTo(Playability.NOW);
        // Next turn the Marcador gives 3
        assertThat(Playability.level(room, "host", three)).isEqualTo(Playability.NEXT_TURN);
        assertThat(Playability.level(room, "host", seven)).isEqualTo(Playability.LATER);
        // Asking does not change the room
        assertThat(EffectEngine.gemsOf(room, "host")).isEqualTo(2);
        assertThat(room.getCurrentTurnUserId()).isEqualTo("host");
    }
}
