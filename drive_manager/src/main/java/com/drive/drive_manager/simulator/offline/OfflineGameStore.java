package com.drive.drive_manager.simulator.offline;

import java.time.Instant;

/**
 * Where the verdicts on offline games are kept ({@code offline_games} in Mongo): one per player and game id, so a game
 * sent again gets the same answer without being played again, and an accepted game is counted once even under another
 * id (one accepted game per player, seed and game of the room).
 */
public interface OfflineGameStore {

    /** A verdict as kept. {@code status}: "accepted" or "rejected". */
    record Verdict(String userId, String gameId, String status, String reason, String message, String seed, int gameNumber,
                   String winnerId, int turns, String digest, String engineBuild, Instant startedAt, Instant endedAt,
                   Instant at, String matchId) {
    }

    /** The verdict already given on this player's game, or null. */
    Verdict find(String userId, String gameId);

    /** Whether this player already has an accepted game with this seed and game number (the same game under another id). */
    boolean acceptedGame(String userId, String seed, int gameNumber);

    /** This player's accepted games since then. */
    long accepted(String userId, Instant since);

    /** This player's games played again since then (accepted and rejected). */
    long replayed(String userId, Instant since);

    /** Keeps a verdict; false when one with its id, or an accepted one of the same game, is there already. */
    boolean insert(Verdict verdict);

    /** The history's id of an accepted game, once it is kept there. */
    void matchKept(String userId, String gameId, String matchId);
}
