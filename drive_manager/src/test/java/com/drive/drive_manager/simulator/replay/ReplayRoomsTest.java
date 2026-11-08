package com.drive.drive_manager.simulator.replay;

import com.drive.drive_manager.simulator.engine.GameRandom;
import com.drive.drive_manager.simulator.bot.BotMatch;
import com.drive.drive_manager.simulator.bot.BotPlayer;
import com.drive.drive_manager.simulator.bot.HeuristicBot;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;
import com.drive.drive_manager.simulator.room.RoomService;
import com.drive.drive_manager.simulator.room.StateMask;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Watching a kept game again: from its start to its end, move by move, read only and for its viewer alone. */
class ReplayRoomsTest {

    private final RoomService service = new RoomService();
    private final MatchReplayTest.Kept kept = new MatchReplayTest.Kept();

    ReplayRoomsTest() {
        ReflectionTestUtils.setField(service, "history", kept);
    }

    private GameStateUpdate act(String roomId, String userId, String type, Map<String, Object> payload) {
        return service.applyAction(roomId, userId, BotPlayer.action(type, payload), false);
    }

    /** A whole game between two heuristic bots, kept. */
    private MatchRecorder.Match keptGame() {
        GameRoom room = service.createRoom("host");
        room.setRandom(new GameRandom(13));
        act(room.getRoomId(), "host", "JOIN_ROOM", Map.of("username", "Ana"));
        act(room.getRoomId(), "guest", "JOIN_ROOM", Map.of("username", "Beto"));
        int[] ids = {0};
        BotMatch.seat(room.getPlayer("host"), "st2", ids);
        BotMatch.seat(room.getPlayer("guest"), "st3", ids);
        Map<String, BotPlayer> bots = Map.of("host", new HeuristicBot(), "guest", new HeuristicBot());
        for (int i = 0; i < BotMatch.MAX_ACTIONS && room.getStatus() != GameRoom.RoomStatus.POST_GAME; i++) {
            for (String seat : List.of("host", "guest")) {
                GameAction a = bots.get(seat).next(room, seat);
                if (a == null) continue;
                GameStateUpdate u = service.applyAction(room.getRoomId(), seat, a, false);
                if (u.getError() != null) bots.get(seat).refused(a, u.getError());
                else bots.get(seat).played(room, a);
                break;
            }
        }
        assertThat(kept.games).hasSize(1);
        return kept.games.get(0);
    }

    @Test
    void aReplayOpensAtTheStartAndReachesTheEndExactly() {
        MatchRecorder.Match game = keptGame();
        GameRoom replay = service.openReplay("host", game.setup(), game.actions());
        String id = replay.getRoomId();
        assertThat(replay.isReplay()).isTrue();
        assertThat(replay.getTimelineAt()).isZero();
        assertThat(replay.getTimelineSize()).isEqualTo(game.actions().size() + 1);
        assertThat(replay.getStatus()).isEqualTo(GameRoom.RoomStatus.MULLIGAN);
        assertThat(service.openRooms()).doesNotContain(replay);

        GameStateUpdate join = act(id, "host", "JOIN_ROOM", Map.of("username", "Ana"));
        assertThat(join.getError()).isNull();
        assertThat(join.isReplay()).isTrue();
        assertThat(replay.getPlayers()).hasSize(2);

        act(id, "host", "TIME_STEP", Map.of("to", game.actions().size()));
        assertThat(replay.getStatus()).isEqualTo(GameRoom.RoomStatus.POST_GAME);
        assertThat(replay.getWinnerId()).isEqualTo(game.winnerId());
        assertThat(MatchReplay.fingerprint(replay)).isEqualTo(game.fingerprint());

        act(id, "host", "TIME_STEP", Map.of("by", -3));
        assertThat(replay.getTimelineAt()).isEqualTo(game.actions().size() - 3);
        assertThat(MatchReplay.fingerprint(replay))
                .isEqualTo(MatchReplay.fingerprint(MatchReplay.play(game.setup(), game.actions(), game.actions().size() - 3, null)));
        // Watching does not record games
        assertThat(kept.games).hasSize(1);
    }

    @Test
    void aReplayIsReadOnlyAndItsViewersAlone() {
        MatchRecorder.Match game = keptGame();
        GameRoom replay = service.openReplay("host", game.setup(), game.actions());
        String id = replay.getRoomId();
        act(id, "host", "TIME_STEP", Map.of("to", 5));
        assertThat(act(id, "host", "END_TURN", Map.of()).getError()).isNotNull();
        assertThat(act(id, "host", "SURRENDER", Map.of()).getError()).isNotNull();
        assertThat(act(id, "guest", "TIME_STEP", Map.of("by", 1)).getError()).isNotNull();
        assertThat(act(id, "guest", "JOIN_ROOM", Map.of("username", "Beto")).getError()).isNotNull();
        // Everything in view for its viewer
        GameStateUpdate u = GameStateUpdate.from(replay, "TIME_STEP", "host");
        assertThat(StateMask.forViewer(u, "host", false)).isSameAs(u);
        // Leaving closes it
        assertThat(act(id, "host", "LEAVE_ROOM", Map.of()).getError()).isNull();
        assertThat(service.getRoom(id)).isNull();
    }
}
