package com.drive.drive_manager.simulator.room;

import com.drive.drive_manager.simulator.bot.BotRooms;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Sala de pruebas' time line: the game as it was after each move (copies of the room), to go back and forth
 * through it. Going back pauses the bots; a new move from the past forgets the future. Only the game travels: the
 * room's settings (its {@link RoomSetting} fields: who plays each side, the bots, their pace, the pause) and the
 * players' connections stay as they are.
 * Kept apart from the rooms so the bot's room copies never carry it. Its lines are only touched with the room's lock
 * held (after an action, or by the owner's controls), so plain lists are enough.
 */
public final class TestTimeline {

    /** Most moves kept per room (the oldest are forgotten). */
    static final int MAX_STEPS = 150;

    private static final class Line {
        final List<GameRoom> steps = new ArrayList<>();
        int at = -1;
    }

    private final BotRooms bots;
    private final Map<String, Line> lines = new ConcurrentHashMap<>();
    private final Random chance = new Random();

    TestTimeline(BotRooms bots) {
        this.bots = bots;
    }

    /** After an accepted action in a test room: the game that starts opens the line, each move is a step of it. */
    void after(GameRoom room, GameRoom.RoomStatus before, GameAction action) {
        if (!room.isEditor() || ActionType.has(action, ActionType.Flag.NOT_A_STEP)) return;
        boolean wasPlaying = before != null && before.isPlaying();
        boolean playing = RoomGuard.playing(room);
        if (!wasPlaying && playing) lines.put(room.getRoomId(), new Line());
        Line line = lines.get(room.getRoomId());
        if (line == null || (!playing && !wasPlaying)) return;
        // A move from the past: its future is forgotten
        while (line.steps.size() > line.at + 1) line.steps.remove(line.steps.size() - 1);
        line.steps.add(RoomCopy.of(room, chance));
        if (line.steps.size() > MAX_STEPS) line.steps.remove(0);
        line.at = line.steps.size() - 1;
        mark(room, line);
    }

    /**
     * The owner's controls: "SET_PAUSED" ("paused": the bots wait) and "TIME_STEP" (to the step "to", or "by" steps
     * back or forth; going back pauses the bots).
     */
    public GameStateUpdate handle(GameRoom room, String userId, GameAction action) {
        if (!room.isEditor()) return GameStateUpdate.error(room.getRoomId(), "Solo en la sala de pruebas");
        if (!userId.equals(room.getHostId())) return GameStateUpdate.error(room.getRoomId(), "Solo el dueño controla el tiempo de la sala");
        if (ActionType.SET_PAUSED.is(action)) {
            room.setPaused(action.getBoolean("paused", true));
            return GameStateUpdate.from(room, ActionType.SET_PAUSED, userId);
        }
        Line line = lines.get(room.getRoomId());
        if (line == null || line.steps.isEmpty()) return GameStateUpdate.error(room.getRoomId(), "Todavía no hay jugadas para recorrer");
        Object to = action.getPayload() == null ? null : action.getPayload().get("to");
        Object by = action.getPayload() == null ? null : action.getPayload().get("by");
        int target = to instanceof Number n ? n.intValue() : line.at + (by instanceof Number n ? n.intValue() : -1);
        target = Math.max(0, Math.min(line.steps.size() - 1, target));
        if (target < line.at) room.setPaused(true);
        restore(room, line.steps.get(target));
        line.at = target;
        mark(room, line);
        bots.fresh(room);
        return GameStateUpdate.from(room, ActionType.TIME_STEP, userId);
    }

    /** Only the lines of the rooms still open are kept. */
    void keepOnly(Set<String> roomIds) {
        lines.keySet().retainAll(roomIds);
    }

    /** The game of {@code step} in the room, with the room's own settings and the players' connections as they are now. */
    private void restore(GameRoom room, GameRoom step) {
        Map<String, PlayerState> now = new HashMap<>(room.getPlayers());
        RoomCopy.restore(room, step, chance);
        room.getPlayers().forEach((id, p) -> {
            PlayerState was = now.get(id);
            if (was == null) return;
            p.setConnected(was.isConnected());
            p.setDisconnectedAt(was.getDisconnectedAt());
            p.setAvatar(was.getAvatar());
            p.setPlaymat(was.getPlaymat());
        });
    }

    private static void mark(GameRoom room, Line line) {
        room.setTimelineAt(line.at);
        room.setTimelineSize(line.steps.size());
    }
}
