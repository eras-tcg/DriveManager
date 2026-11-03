package com.drive.drive_manager.simulator;

import com.drive.drive_manager.service.ActivityLogService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The games a room's action puts in the activity log. */
class GameActivityTest {

    private static GameRoom room(GameRoom.RoomStatus status, boolean editor) {
        GameRoom room = new GameRoom();
        room.setRoomId("r1");
        room.setStatus(status);
        room.setEditor(editor);
        return room;
    }

    private static List<String> players(String... ids) {
        return List.of(ids);
    }

    @Test
    void anOnlineGameStartsForBothPlayersWithTheirRival() {
        List<GameActivity.Event> events = GameActivity.of(room(GameRoom.RoomStatus.SELECTING_STARTER, false),
                GameRoom.RoomStatus.WAITING, players("alice", "bob"));

        assertThat(events).containsExactly(
                new GameActivity.Event("alice", ActivityLogService.ONLINE_GAME_START, Map.of("roomId", "r1", "rivalId", "bob")),
                new GameActivity.Event("bob", ActivityLogService.ONLINE_GAME_START, Map.of("roomId", "r1", "rivalId", "alice")));
    }

    @Test
    void anOnlineGameEndsWithEachPlayersResultEvenForWhoeverLeft() {
        GameRoom room = room(GameRoom.RoomStatus.POST_GAME, false);
        room.setWinnerId("alice");

        List<GameActivity.Event> events = GameActivity.of(room, GameRoom.RoomStatus.IN_PROGRESS, players("alice", "bob"));

        assertThat(events).extracting(e -> e.detail().get("result")).containsExactly("won", "lost");
        assertThat(events).extracting(GameActivity.Event::action).containsOnly(ActivityLogService.ONLINE_GAME_END);
    }

    @Test
    void aSoloGameCountsOnlyItsOwner() {
        List<GameActivity.Event> started = GameActivity.of(room(GameRoom.RoomStatus.SELECTING_STARTER, true),
                GameRoom.RoomStatus.WAITING, players("alice", RoomService.EDITOR_RIVAL));
        List<GameActivity.Event> ended = GameActivity.of(room(GameRoom.RoomStatus.POST_GAME, true),
                GameRoom.RoomStatus.IN_PROGRESS, players("alice", RoomService.EDITOR_RIVAL));

        assertThat(started).containsExactly(new GameActivity.Event("alice", ActivityLogService.SOLO_GAME_START, Map.of("roomId", "r1")));
        assertThat(ended).containsExactly(new GameActivity.Event("alice", ActivityLogService.SOLO_GAME_END, Map.of("roomId", "r1")));
    }

    @Test
    void anActionThatNeitherStartsNorEndsAGameLogsNothing() {
        assertThat(GameActivity.of(room(GameRoom.RoomStatus.IN_PROGRESS, false), GameRoom.RoomStatus.IN_PROGRESS, players("alice", "bob"))).isEmpty();
        assertThat(GameActivity.of(room(GameRoom.RoomStatus.WAITING, false), GameRoom.RoomStatus.POST_GAME, players("alice", "bob"))).isEmpty();
    }
}
