package com.drive.drive_manager.simulator.room;

import com.drive.drive_manager.simulator.bot.BotMatch;
import com.drive.drive_manager.simulator.bot.BotPlayer;
import com.drive.drive_manager.simulator.bot.HeuristicBot;
import com.drive.drive_manager.simulator.replay.MatchReplay;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Sala de pruebas' time line: going back and forth through the moves gives the game exactly as it was, a move
 * from the past forgets the future, going back pauses the bots, and the room's settings never travel.
 */
class TestTimelineTest {

    private final RoomService service = new RoomService();
    private GameRoom room;
    private String id;
    /** The game's fingerprint after its start (0) and after each move. */
    private final List<String> seen = new ArrayList<>();

    private GameStateUpdate act(String type, Map<String, Object> payload) {
        return service.applyAction(id, "owner", BotPlayer.action(type, payload), false);
    }

    /** A test room whose two sides are played by heuristic bots, {@code moves} moves into its game. */
    private void playTestGame(int moves) {
        room = service.createRoom("owner", RoomOptions.TEST);
        id = room.getRoomId();
        room.setRandom(new java.util.Random(4));
        act("JOIN_ROOM", Map.of("username", "Ana"));
        int[] ids = {0};
        BotMatch.seat(room.getPlayer("owner"), "st1", ids);
        BotMatch.seat(room.getPlayer(RoomService.EDITOR_RIVAL), "st3", ids);
        act("SET_READY", Map.of("ready", true));
        assertThat(room.getTimelineSize()).isEqualTo(1);
        seen.add(MatchReplay.fingerprint(room));
        Map<String, BotPlayer> bots = Map.of("owner", new HeuristicBot(), RoomService.EDITOR_RIVAL, new HeuristicBot());
        while (seen.size() <= moves) assertThat(move(bots)).as("a move").isTrue();
    }

    /** One accepted move of whichever side must act (the rival's sent as the owner acting for it). */
    private boolean move(Map<String, BotPlayer> bots) {
        for (Map.Entry<String, BotPlayer> seat : bots.entrySet()) {
            GameAction a = seat.getValue().next(room, seat.getKey());
            if (a == null) continue;
            Map<String, Object> payload = new HashMap<>(a.getPayload() == null ? Map.of() : a.getPayload());
            if (!seat.getKey().equals("owner")) payload.put("actAs", RoomService.EDITOR_RIVAL);
            GameStateUpdate u = act(a.getType(), payload);
            if (u.getError() != null) {
                seat.getValue().refused(a, u.getError());
                return true;
            }
            seat.getValue().played(room, a);
            seen.add(MatchReplay.fingerprint(room));
            return true;
        }
        return false;
    }

    @Test
    void goingBackAndForthShowsTheGameExactlyAsItWas() {
        playTestGame(30);
        assertThat(room.getTimelineSize()).isEqualTo(seen.size());
        assertThat(act("TIME_STEP", Map.of("to", 10)).getError()).isNull();
        assertThat(MatchReplay.fingerprint(room)).isEqualTo(seen.get(10));
        assertThat(room.getTimelineAt()).isEqualTo(10);
        act("TIME_STEP", Map.of("by", 5));
        assertThat(MatchReplay.fingerprint(room)).isEqualTo(seen.get(15));
        act("TIME_STEP", Map.of("by", -1));
        assertThat(MatchReplay.fingerprint(room)).isEqualTo(seen.get(14));
        // Back to the present
        act("TIME_STEP", Map.of("to", seen.size() - 1));
        assertThat(MatchReplay.fingerprint(room)).isEqualTo(seen.get(seen.size() - 1));
    }

    @Test
    void aMoveFromThePastForgetsTheFuture() {
        playTestGame(20);
        act("TIME_STEP", Map.of("to", 8));
        int before = room.getTimelineSize();
        assertThat(before).isEqualTo(21);
        seen.subList(9, seen.size()).clear();
        assertThat(move(Map.of("owner", new HeuristicBot(), RoomService.EDITOR_RIVAL, new HeuristicBot()))).isTrue();
        assertThat(room.getTimelineSize()).isEqualTo(10);
        assertThat(room.getTimelineAt()).isEqualTo(9);
    }

    @Test
    void goingBackPausesTheBotsAndTheRoomsSettingsStay() {
        playTestGame(12);
        act("SET_BOT_DELAY", Map.of("ms", 700));
        act("SET_EDITOR_BOT", Map.of("on", true));
        act("SET_PAUSED", Map.of("paused", false));
        act("TIME_STEP", Map.of("to", 3));
        assertThat(room.isPaused()).isTrue();
        assertThat(room.getBotDelayMs()).isEqualTo(700);
        assertThat(room.isEditorBot()).isTrue();
        assertThat(room.isEditor()).isTrue();
        assertThat(room.getPlayer("owner").isConnected()).isTrue();
        // Paused, the bot does not move
        assertThat(service.playBotNow(id)).isZero();
        act("SET_PAUSED", Map.of("paused", false));
        assertThat(room.isPaused()).isFalse();
    }

    @Test
    void onlyInATestRoomAndOnlyItsOwner() {
        GameRoom online = service.createRoom("owner", RoomOptions.ONLINE);
        id = online.getRoomId();
        act("JOIN_ROOM", Map.of("username", "Ana"));
        assertThat(act("TIME_STEP", Map.of("by", -1)).getError()).isNotNull();
        assertThat(act("SET_PAUSED", Map.of("paused", true)).getError()).isNotNull();
    }
}
