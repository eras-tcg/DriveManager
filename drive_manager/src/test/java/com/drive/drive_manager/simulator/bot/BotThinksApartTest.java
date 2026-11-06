package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.RoomOptions;
import com.drive.drive_manager.simulator.room.RoomService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** A bot thinks on a copy of the room, without holding it: the owner keeps acting meanwhile, and a stale thought is dropped. */
class BotThinksApartTest {

    /** A bot that takes its time to think (and plays nothing). */
    static final class SlowBot extends BotPlayer {
        final CountDownLatch thinking = new CountDownLatch(1);
        volatile GameRoom seen;

        @Override
        public GameAction next(GameRoom room, String me) {
            seen = room;
            thinking.countDown();
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theOwnerActsWhileTheBotThinksOnACopy() throws Exception {
        RoomService service = new RoomService();
        GameRoom room = service.createRoom("owner", RoomOptions.TEST);
        String id = room.getRoomId();
        service.applyAction(id, "owner", BotPlayer.action("JOIN_ROOM", Map.of("username", "Ana")), false);
        int[] ids = {0};
        BotMatch.seat(room.getPlayer("owner"), "st1", ids);
        BotMatch.seat(room.getPlayer(RoomService.EDITOR_RIVAL), "st3", ids);
        service.applyAction(id, "owner", BotPlayer.action("SET_READY", Map.of("ready", true)), false);
        service.applyAction(id, "owner", BotPlayer.action("SET_EDITOR_BOT", Map.of("on", true)), false);
        service.applyAction(id, "owner", BotPlayer.action("SET_BOT_DELAY", Map.of("ms", 0)), false);
        Object botRooms = ReflectionTestUtils.getField(service, "botRooms");
        Map<String, BotPlayer> bots = (Map<String, BotPlayer>) ReflectionTestUtils.getField(botRooms, "bots");
        SlowBot slow = new SlowBot();
        bots.put(id, slow);

        // Woken by an action, the bot starts thinking on its own thread
        service.applyAndBroadcast(id, "owner", BotPlayer.action("SET_SKIP_OPTIONAL", Map.of("skip", true)), false, false);
        assertThat(slow.thinking.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(slow.seen).isNotSameAs(room);

        // Meanwhile the owner's action does not wait for it
        long start = System.nanoTime();
        service.applyAndBroadcast(id, "owner", BotPlayer.action("SET_PAUSED", Map.of("paused", true)), false, false);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(room.isPaused()).isTrue();
        assertThat(ms).as("ms the owner waited").isLessThan(500);
    }
}
