package com.drive.drive_manager.simulator.rank;

import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.RoomOptions;
import com.drive.drive_manager.simulator.room.RoomService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * The ranked queue (Online): players wait to be paired with a rival of a similar strength (MMR). The gap accepted
 * (the window) starts narrow and grows while they wait; once paired, a ranked room is made for the two of them alone
 * (reserved: not listed, nobody else enters, always ranked). Who stops asking for a while leaves the queue.
 */
@org.springframework.stereotype.Component
public class RankedQueue {

    /** The gap of strength accepted at first, how much it grows every {@link #GROW_EVERY_MS} waited, and its most. */
    static final int START_WINDOW = 100, GROW = 50, MAX_WINDOW = 600;
    static final long GROW_EVERY_MS = 10_000;
    /** Who has not asked for this long has left. */
    static final long GONE_AFTER_MS = 10_000;
    /** A match is kept to be told to its players for this long. */
    static final long MATCH_KEPT_MS = 60_000;

    /** A player waiting: their strength, since when they wait and when they asked last. */
    record Waiting(String userId, String username, int mmr, long since, long seen) {}

    /** What the player's search is: waiting (with the time waited and the window), matched (the room) or none. */
    public record Status(String state, long waitedMs, int window, String roomId) {
        static final Status NONE = new Status("none", 0, 0, null);
    }

    private record Matched(String roomId, long at) {}

    private final RoomService rooms;
    private final ToIntFunction<String> strength;
    private final Map<String, Waiting> waiting = new LinkedHashMap<>();
    private final Map<String, Matched> matched = new HashMap<>();

    /** {@code strength}: a player's MMR (the ranked ladder's). */
    RankedQueue(RoomService rooms, ToIntFunction<String> strength) {
        this.rooms = rooms;
        this.strength = strength;
    }

    /** The server's queue: strengths from the ranked ladder. */
    @org.springframework.beans.factory.annotation.Autowired
    public RankedQueue(RoomService rooms, RankService ranks) {
        this(rooms, userId -> ranks.of(userId).mmr());
    }

    /** The player enters the queue (or stays, if already in it). */
    public Status join(String userId, String username, long now) {
        // The strength is read first (the database), not while holding the queue
        int mmr = strength.applyAsInt(userId);
        synchronized (this) {
            matched.remove(userId);
            Waiting w = waiting.get(userId);
            waiting.put(userId, w != null ? new Waiting(userId, username, w.mmr(), w.since(), now) : new Waiting(userId, username, mmr, now, now));
            pair(now);
            return status(userId, now);
        }
    }

    /** The player asks how their search goes (it keeps them in the queue). */
    public synchronized Status poll(String userId, long now) {
        Waiting w = waiting.get(userId);
        if (w != null) waiting.put(userId, new Waiting(w.userId(), w.username(), w.mmr(), w.since(), now));
        pair(now);
        return status(userId, now);
    }

    /** The player leaves the queue. */
    public synchronized void leave(String userId) {
        waiting.remove(userId);
        matched.remove(userId);
    }

    /** The gap of strength accepted after waiting {@code waitedMs}. */
    static int window(long waitedMs) {
        return (int) Math.min(MAX_WINDOW, START_WINDOW + GROW * (waitedMs / GROW_EVERY_MS));
    }

    private Status status(String userId, long now) {
        Matched m = matched.get(userId);
        if (m != null) return new Status("matched", 0, 0, m.roomId());
        Waiting w = waiting.get(userId);
        return w == null ? Status.NONE : new Status("waiting", now - w.since(), window(now - w.since()), null);
    }

    /** Pairs who can play: the ones waiting longest first, each with the closest rival within the wider window. */
    private void pair(long now) {
        waiting.values().removeIf(w -> now - w.seen() > GONE_AFTER_MS);
        matched.values().removeIf(m -> now - m.at() > MATCH_KEPT_MS);
        List<Waiting> queue = new ArrayList<>(waiting.values());
        queue.sort(Comparator.comparingLong(Waiting::since));
        for (Waiting a : queue) {
            if (!waiting.containsKey(a.userId())) continue;
            Waiting best = null;
            for (Waiting b : queue) {
                if (b == a || !waiting.containsKey(b.userId())) continue;
                int gap = Math.abs(a.mmr() - b.mmr());
                int allowed = Math.max(window(now - a.since()), window(now - b.since()));
                if (gap <= allowed && (best == null || gap < Math.abs(a.mmr() - best.mmr()))) best = b;
            }
            if (best != null) start(a, best, now);
        }
    }

    /** A ranked room for the two (the one who waited longest owns it), reserved for them. */
    private void start(Waiting a, Waiting b, long now) {
        waiting.remove(a.userId());
        waiting.remove(b.userId());
        GameRoom room = rooms.createRoom(a.userId(), RoomOptions.RANKED);
        room.setReserved(Set.of(a.userId(), b.userId()));
        matched.put(a.userId(), new Matched(room.getRoomId(), now));
        matched.put(b.userId(), new Matched(room.getRoomId(), now));
    }

    /** How many wait (tests). */
    synchronized int size() {
        return waiting.size();
    }
}
