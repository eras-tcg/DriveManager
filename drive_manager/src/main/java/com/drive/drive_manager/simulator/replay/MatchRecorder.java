package com.drive.drive_manager.simulator.replay;

import com.drive.drive_manager.simulator.room.ActionType;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.RoomGuard;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps each game being played as it goes: how it started ({@link MatchSetup}) and every action accepted after,
 * the bots' and the abandons included, so the game can be played again exactly ({@link MatchReplay}). When the game
 * ends the record goes to the history ({@link MatchHistory}); test rooms are not recorded. Kept apart from the rooms
 * so the bot's room copies never carry it.
 */
public final class MatchRecorder {

    /** An action as it was applied: who did it (the seat it was played for) and whether manual actions were allowed. */
    public record Played(String userId, String type, Map<String, Object> payload, boolean manual) {}

    /** A whole game: how it started, what was played and how it ended. */
    public record Match(String roomId, MatchSetup setup, List<Played> actions, Instant startedAt, Instant endedAt,
                        String firstPlayerId, String winnerId, String endReason, int turns, String fingerprint,
                        String botLevel) {}

    /** The action that stands for a player leaving an online game for good (the other wins). */
    public static final String ABANDON = "ABANDON";
    /** Actions that do not change the game (entering again, the bots' settings): not recorded. */
    private static final Set<ActionType> NOT_GAME = java.util.EnumSet.of(ActionType.JOIN_ROOM, ActionType.SET_BOT_LEVEL,
            ActionType.SET_BOT_DELAY, ActionType.SET_BOT_EDGE, ActionType.SET_EDITOR_BOT, ActionType.SET_EDITOR_SELF_BOT, ActionType.REFRESH_CARD_METADATA);

    private record Active(MatchSetup setup, List<Played> actions, Instant startedAt) {}

    private final Map<String, Active> active = new ConcurrentHashMap<>();

    /**
     * After an accepted action: the game it started begins to be recorded, an action of a game in course is kept and,
     * when the game is over, it is handed to {@code history} (none in tests and in the bot's sandbox). Returns the
     * game when this action ended it.
     */
    public Match after(GameRoom room, GameRoom.RoomStatus before, String userId, GameAction action, boolean manual, MatchHistory history) {
        boolean wasPlaying = before != null && before.isPlaying();
        boolean playing = RoomGuard.playing(room);
        Active game = active.get(room.getRoomId());
        if (!wasPlaying && playing && !room.isEditor() && room.getStartSetup() != null) {
            active.put(room.getRoomId(), new Active(room.getStartSetup(), new ArrayList<>(), Instant.now()));
            return null;
        }
        if (game == null || !wasPlaying || NOT_GAME.contains(ActionType.of(action))) return null;
        if (ActionType.KICK_PLAYER.is(action)) game.actions().add(new Played(action.getString("userId"), ABANDON, Map.of(), false));
        else game.actions().add(new Played(userId, action.getType(), payloadOf(action), manual));
        return playing ? null : finish(room, game, history);
    }

    /** An online game's player left it for good (disconnected too long): the game ends with it. */
    public Match abandon(GameRoom room, String userId, MatchHistory history) {
        Active game = active.get(room.getRoomId());
        if (game == null) return null;
        game.actions().add(new Played(userId, ABANDON, Map.of(), false));
        return RoomGuard.playing(room) ? null : finish(room, game, history);
    }

    /** Only the games of the rooms still open are kept (a room closed in the middle of a game loses it). */
    public void keepOnly(Set<String> roomIds) {
        active.keySet().retainAll(roomIds);
    }

    /** The game being recorded in the room, if any (tests). */
    List<Played> actionsOf(String roomId) {
        Active game = active.get(roomId);
        return game == null ? null : List.copyOf(game.actions());
    }

    private Match finish(GameRoom room, Active game, MatchHistory history) {
        active.remove(room.getRoomId());
        int turns = (int) room.getTimeline().stream()
                .filter(e -> com.drive.drive_manager.simulator.engine.GameEvent.TURN_START.equals(e.getType())).count() + 1;
        Match match = new Match(room.getRoomId(), game.setup(), List.copyOf(game.actions()), game.startedAt(), Instant.now(),
                room.getFirstPlayerId(), room.getWinnerId(), room.getEndReason(), turns, MatchReplay.fingerprint(room),
                room.isBot() ? room.getBotLevel() : null);
        if (history != null) history.save(match);
        return match;
    }

    /** The action's payload as it came (a copy: nothing that changes later reaches the record). */
    private static Map<String, Object> payloadOf(GameAction action) {
        return action.getPayload() == null ? Map.of() : new HashMap<>(action.getPayload());
    }
}
