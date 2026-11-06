package com.drive.drive_manager.simulator.replay;

import com.drive.drive_manager.simulator.room.ActionType;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;
import com.drive.drive_manager.simulator.room.RoomCopy;
import com.drive.drive_manager.simulator.room.RoomService;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rooms to watch a kept game again (from its log): the board shows the game as it was at one of its moves, and the
 * viewer goes back and forth through them. Read only: no move of the game can be made. To show a move, the game is
 * played again up to it on a room service of its own (a few milliseconds), so no copy of each move is kept. Everything
 * is shown (the game is over), only to the viewer, who opened it.
 */
public final class ReplayRooms {

    /** How long a replay room lives (the viewer opens another one to watch again). */
    public static final java.time.Duration LIFETIME = java.time.Duration.ofHours(1);

    private record Game(MatchSetup setup, List<MatchRecorder.Played> actions) {}

    private final Map<String, Game> games = new ConcurrentHashMap<>();
    private final Random chance = new Random();

    /** A replay room of the game for its viewer, shown at its start (step 0). */
    public GameRoom open(String viewerId, MatchSetup setup, List<MatchRecorder.Played> actions) {
        GameRoom room = setup.toRoom(java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        room.setReplay(true);
        room.setEditor(false);
        room.setHostId(viewerId);
        room.setEmptySince(java.time.Instant.now());
        games.put(room.getRoomId(), new Game(setup, List.copyOf(actions)));
        show(room, 0);
        return room;
    }

    /** An action of the viewer in a replay room: only watching. */
    public GameStateUpdate act(RoomService service, GameRoom room, String userId, GameAction action) {
        if (!userId.equals(room.getHostId())) return GameStateUpdate.error(room.getRoomId(), "Esta repetición es de otro jugador");
        if (!ActionType.has(action, ActionType.Flag.REPLAY)) return GameStateUpdate.error(room.getRoomId(), "Es una repetición: no se puede jugar");
        Game game = games.get(room.getRoomId());
        if (game == null) return GameStateUpdate.error(room.getRoomId(), "La repetición ya no está disponible");
        switch (ActionType.of(action)) {
            case JOIN_ROOM -> room.setEmptySince(null);
            case LEAVE_ROOM -> {
                room.setStatus(GameRoom.RoomStatus.FINISHED);
                service.drop(room.getRoomId());
                games.remove(room.getRoomId());
                return GameStateUpdate.from(room, "ROOM_CLOSED", userId);
            }
            case TIME_STEP -> {
                Object to = action.getPayload() == null ? null : action.getPayload().get("to");
                Object by = action.getPayload() == null ? null : action.getPayload().get("by");
                int target = to instanceof Number n ? n.intValue() : room.getTimelineAt() + (by instanceof Number n ? n.intValue() : -1);
                show(room, Math.max(0, Math.min(game.actions().size(), target)));
            }
            case null, default -> { }
        }
        return GameStateUpdate.from(room, action.getType(), userId);
    }

    /** Only the games of the rooms still open are kept. */
    public void keepOnly(Set<String> roomIds) {
        games.keySet().retainAll(roomIds);
    }

    /** The room shows the game after its first {@code step} actions (0: right after it started). */
    private void show(GameRoom room, int step) {
        Game game = games.get(room.getRoomId());
        GameRoom played = MatchReplay.play(game.setup(), game.actions(), step, null);
        RoomCopy.restore(room, played, chance);
        room.setTimelineAt(step);
        // Steps 0 … actions: one more than the actions
        room.setTimelineSize(game.actions().size() + 1);
    }
}
