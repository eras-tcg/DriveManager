package com.drive.drive_manager.simulator.room;

import com.drive.drive_manager.simulator.replay.ReplayRooms;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The open rooms, by id, and the players' STOMP sessions in them: lookup, listing, and closing the rooms left empty
 * too long (and the replays past their lifetime). Holds no lock of its own: the rooms are locked by whoever acts on them.
 */
final class RoomRegistry {

    private final ConcurrentHashMap<String, GameRoom> rooms = new ConcurrentHashMap<>();

    // STOMP sessionId -> [roomId, userId], so a SessionDisconnectEvent (browser
    // closed / network drop) can be resolved back to a player in a room.
    private final ConcurrentHashMap<String, String[]> sessions = new ConcurrentHashMap<>();

    /** A room still open, or null (no pruning first). */
    GameRoom get(String roomId) {
        return rooms.get(roomId);
    }

    void put(GameRoom room) {
        rooms.put(room.getRoomId(), room);
    }

    void remove(String roomId) {
        rooms.remove(roomId);
    }

    /** The open rooms' ids (a live view). */
    Set<String> ids() {
        return rooms.keySet();
    }

    /** A snapshot of the open rooms, to go through while they change. */
    List<GameRoom> snapshot() {
        return new ArrayList<>(rooms.values());
    }

    /** Rooms that can be listed to enter: open ones, newest first. */
    List<GameRoom> listed() {
        return rooms.values().stream()
                .filter(r -> r.getStatus() != GameRoom.RoomStatus.FINISHED && !r.isEditor() && !r.isBot() && !r.isReplay() && r.getReserved().isEmpty())
                .sorted(Comparator.comparing(GameRoom::getCreatedAt).reversed())
                .toList();
    }

    /** Rooms with someone connected. */
    long active() {
        return rooms.values().stream().filter(r -> r.getPlayers().values().stream().anyMatch(PlayerState::isConnected)).count();
    }

    /**
     * Closes the replays past their lifetime and the rooms empty for longer than {@link SimulatorLimits#EMPTY_ROOM_TTL};
     * {@code abandoned} sees each closed ranked game against the bot still being played (its owner gone for good).
     */
    void prune(Consumer<GameRoom> abandoned) {
        Instant limit = Instant.now().minus(SimulatorLimits.EMPTY_ROOM_TTL);
        rooms.values().removeIf(r -> {
            if (r.isReplay() && r.getCreatedAt().isBefore(Instant.now().minus(ReplayRooms.LIFETIME))) return true;
            if (r.getEmptySince() == null || !r.getEmptySince().isBefore(limit)) return false;
            if (r.isBot() && r.isRanked() && RoomGuard.playing(r)) abandoned.accept(r);
            return true;
        });
    }

    // ------------------------------------------------------------------ Sessions

    void registerSession(String sessionId, String roomId, String userId) {
        sessions.put(sessionId, new String[]{roomId, userId});
    }

    /** The [roomId, userId] of a session that dropped, forgotten now; null if unknown. */
    String[] dropSession(String sessionId) {
        return sessions.remove(sessionId);
    }

    /** Whether the player still has a session in the room (a second tab, a reload that connected first). */
    boolean hasSession(String roomId, String userId) {
        return sessions.values().stream().anyMatch(s -> roomId.equals(s[0]) && userId.equals(s[1]));
    }

    /** Forgets the player's sessions in the room (they left it). */
    void forgetSessions(String roomId, String userId) {
        sessions.values().removeIf(s -> roomId.equals(s[0]) && userId.equals(s[1]));
    }
}
