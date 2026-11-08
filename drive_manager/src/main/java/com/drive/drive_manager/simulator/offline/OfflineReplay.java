package com.drive.drive_manager.simulator.offline;

import com.drive.drive_manager.simulator.room.GameRoom;
import com.eras.engine.local.LocalMatch;
import com.eras.engine.local.LocalMatchRoom;

import java.util.List;

/**
 * Plays an offline game again on the JVM with the engine that played it in the browser (eras-engine's LocalMatch, the
 * same common code), driven exactly as the browser's worker drove it: each action of the player sent, then the bot's
 * moves made after it one by one ({@code step()}), as many as the record says. The same seed, decks and order give the
 * same game, move for move (the bot's thinking is counted in clock readings, not in time: LocalMatch's "ticks").
 * <p>
 * Bounded: a deadline checked between every action and every move of the bot (one move of the bot thinks at most its
 * level's budget), and a bot that has no move where the record says it moved ends the replay at once.
 */
final class OfflineReplay {

    private OfflineReplay() {
    }

    /** Why a replay stopped short, or null. */
    enum Failure { BUDGET, NO_BOT_MOVE }

    /**
     * What the replay gave: the match and its room (null when it could not be made), when the last game of the room
     * started (its timeline's last event before it: what is counted for missions) and why it stopped short, if it did.
     */
    record Outcome(LocalMatch match, GameRoom room, long gameStartSeq, Failure failure, String error) {
        boolean complete() {
            return failure == null && error == null;
        }
    }

    static Outcome play(String config, List<OfflineGame.Step> steps, long deadlineNanos) {
        LocalMatch match;
        try {
            match = new LocalMatch(config);
        } catch (RuntimeException e) {
            return new Outcome(null, null, 0, null, "The match could not be made: " + e.getMessage());
        }
        GameRoom room = LocalMatchRoom.of(match);
        long startSeq = 0;
        for (OfflineGame.Step step : steps) {
            if (System.nanoTime() > deadlineNanos) return new Outcome(match, room, startSeq, Failure.BUDGET, null);
            GameRoom.RoomStatus before = room.getStatus();
            long seq = room.getEventSeq();
            match.send(step.body());
            startSeq = started(before, room, seq, startSeq);
            for (int i = 0; i < step.botMoves(); i++) {
                if (System.nanoTime() > deadlineNanos) return new Outcome(match, room, startSeq, Failure.BUDGET, null);
                before = room.getStatus();
                seq = room.getEventSeq();
                if (match.step() == null) return new Outcome(match, room, startSeq, Failure.NO_BOT_MOVE, null);
                startSeq = started(before, room, seq, startSeq);
            }
        }
        return new Outcome(match, room, startSeq, null, null);
    }

    /** A game that began in this step: its events count from the timeline's last one before it. */
    private static long started(GameRoom.RoomStatus before, GameRoom room, long seqBefore, long startSeq) {
        return !before.isPlaying() && room.getStatus().isPlaying() ? seqBefore : startSeq;
    }
}
