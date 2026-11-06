package com.drive.drive_manager.simulator.rank;

import com.drive.drive_manager.simulator.bot.BotPlayer;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.RoomService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The ranked queue: pairing by strength with a window that grows while waiting, and the room reserved for the two. */
class RankedQueueTest {

    private final RoomService rooms = new RoomService();
    private final Map<String, Integer> mmr = Map.of("ana", 1000, "beto", 1080, "caro", 1400, "dani", 1010);
    private final RankedQueue queue = new RankedQueue(rooms, id -> mmr.getOrDefault(id, 1000));

    @Test
    void twoPlayersOfASimilarStrengthArePairedAtOnceInAReservedRankedRoom() {
        assertThat(queue.join("ana", "Ana", 0).state()).isEqualTo("waiting");
        RankedQueue.Status beto = queue.join("beto", "Beto", 1000);
        assertThat(beto.state()).isEqualTo("matched");
        RankedQueue.Status ana = queue.poll("ana", 1500);
        assertThat(ana.roomId()).isEqualTo(beto.roomId());

        GameRoom room = rooms.getRoom(ana.roomId());
        assertThat(room.isRanked()).isTrue();
        assertThat(room.getHostId()).isEqualTo("ana");
        assertThat(room.getReserved()).containsExactlyInAnyOrder("ana", "beto");
        assertThat(rooms.openRooms()).doesNotContain(room);
        assertThat(queue.size()).isZero();
    }

    @Test
    void onlyTheTwoEnterTheRoomAndItStaysRanked() {
        queue.join("ana", "Ana", 0);
        String id = queue.join("beto", "Beto", 0).roomId();
        assertThat(rooms.applyAction(id, "ana", BotPlayer.action("JOIN_ROOM", Map.of("username", "Ana")), false).getError()).isNull();
        assertThat(rooms.applyAction(id, "beto", BotPlayer.action("JOIN_ROOM", Map.of("username", "Beto")), false).getError()).isNull();
        assertThat(rooms.applyAction(id, "caro", BotPlayer.action("JOIN_ROOM", Map.of("username", "Caro")), false).getError()).isNotNull();
        assertThat(rooms.applyAction(id, "ana", BotPlayer.action("SET_RANKED", Map.of("ranked", false)), false).getError()).isNotNull();
    }

    @Test
    void aFarRivalIsPairedOnlyOnceTheWindowHasGrown() {
        queue.join("ana", "Ana", 0);
        queue.join("caro", "Caro", 0);
        // 400 apart: not while the window is narrower; both keep asking every 5 s
        long now = 0;
        for (; now < 60_000; now += 5_000) {
            assertThat(queue.poll("caro", now).state()).isEqualTo("waiting");
            assertThat(queue.poll("ana", now).state()).isEqualTo("waiting");
        }
        // After 60 s the window is ±400
        assertThat(RankedQueue.window(now)).isEqualTo(400);
        queue.poll("caro", now);
        assertThat(queue.poll("ana", now).state()).isEqualTo("matched");
    }

    @Test
    void theClosestRivalIsChosen() {
        queue.join("ana", "Ana", 0);
        queue.join("caro", "Caro", 0);
        queue.join("beto", "Beto", 0);
        // Ana and Beto (80 apart) are paired; Caro (400 from both) waits
        assertThat(queue.poll("ana", 100).state()).isEqualTo("matched");
        assertThat(queue.poll("beto", 100).state()).isEqualTo("matched");
        assertThat(queue.poll("caro", 100).state()).isEqualTo("waiting");
    }

    @Test
    void whoStopsAskingOrLeavesIsOutOfTheQueue() {
        queue.join("ana", "Ana", 0);
        queue.leave("ana");
        assertThat(queue.poll("ana", 10).state()).isEqualTo("none");
        queue.join("caro", "Caro", 0);
        // Ana comes back later; Caro stopped asking long ago
        queue.join("ana", "Ana", RankedQueue.GONE_AFTER_MS + 5_000);
        assertThat(queue.size()).isEqualTo(1);
        assertThat(queue.poll("caro", RankedQueue.GONE_AFTER_MS + 5_000).state()).isEqualTo("none");
    }

    @Test
    void theWindowGrowsUpToItsMost() {
        assertThat(RankedQueue.window(0)).isEqualTo(RankedQueue.START_WINDOW);
        assertThat(RankedQueue.window(15_000)).isEqualTo(150);
        assertThat(RankedQueue.window(600_000)).isEqualTo(RankedQueue.MAX_WINDOW);
    }
}
