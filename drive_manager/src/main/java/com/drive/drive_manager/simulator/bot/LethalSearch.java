package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.room.ActionType;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;
import com.drive.drive_manager.simulator.room.PlayerState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * lethalLives n (the user's st2 game, 2026-11-05): with the rival at n lives or fewer, the turn is first searched for a
 * win by the engine itself, before the look-ahead's tries: summons (normal and special: an Evolución enters the Línea
 * frontal and attacks at once), cards played and direct attacks, up to MAX_DEPTH moves in a row, each played on a copy
 * of the room (what it opens answered by the heuristic on both sides, the rival's blocks included). A line that wins
 * on its deal is played again on two other deals (the rival's hidden hand and lives dealt again: Reforzar may save a
 * life) and kept when it wins on two of the three. The tries found it too, given time; but four moves in a row
 * (evolve, attack, evolve, attack) are past what the sequences screen when the time is short, and the bot passed with
 * the game won at hand. The line joins the candidates first, so the tries judge it and, if time runs out, it stays.
 */
final class LethalSearch {

    private static final int MAX_DEPTH = 6;
    private static final int MAX_NODES = 160;
    private static final long MAX_NANOS = 300_000_000L;

    private LethalSearch() {}

    /** A winning sequence of moves this turn, or null (none found, or the rival has more lives than lethalLives). */
    static GameAction find(Sandbox sandbox, GameRoom room, String me, BotSettings s) {
        int lives = (int) s.get("lethalLives");
        PlayerState rival = room.getPlayer(room.getOpponentId(me));
        if (lives <= 0 || rival == null || rival.getLifeStack().size() > lives || !me.equals(room.getCurrentTurnUserId())
                || room.getPrompt() != null || room.getStatus() != GameRoom.RoomStatus.IN_PROGRESS) return null;
        Map<String, BotPlayer> sides = Map.of(me, new HeuristicBot(s), room.getOpponentId(me), new HeuristicBot(s));
        long deadline = System.nanoTime() + MAX_NANOS;
        int[] nodes = {0};
        long seed = sandbox.deal();
        List<GameAction> line = dfs(sandbox, room, me, sides, new ArrayList<>(), seed, nodes, deadline);
        if (line == null) return null;
        int wins = 1;
        for (int k = 0; k < 2; k++) if (wins(sandbox, room, me, sides, line, sandbox.deal())) wins++;
        return wins >= 2 ? SequenceSearch.of(line) : null;
    }

    private static List<GameAction> dfs(Sandbox sandbox, GameRoom room, String me, Map<String, BotPlayer> sides,
                                        List<GameAction> line, long seed, int[] nodes, long deadline) {
        if (line.size() >= MAX_DEPTH) return null;
        List<GameAction> moves = new ArrayList<>(LookaheadBot.mainMoves(room, me).stream().filter(m -> switch (ActionType.of(m)) {
            case ATTACK -> m.getString("targetInstanceId") == null;
            case RITUAL_SUMMON, NORMAL_SUMMON, PLAY_CARD -> true;
            case null, default -> false;
        }).toList());
        // Attacks first: a win found sooner costs fewer copies
        moves.sort(Comparator.comparingInt(m -> ActionType.ATTACK.is(m) ? 0 : ActionType.RITUAL_SUMMON.is(m) ? 1 : 2));
        for (GameAction m : moves) {
            if (++nodes[0] > MAX_NODES || System.nanoTime() > deadline) return null;
            GameRoom copy = sandbox.fork(room, me, new Random(seed));
            try {
                GameStateUpdate u = sandbox.act(copy, me, m);
                if (u != null && u.getError() != null) continue;
                SequenceSearch.settle(sandbox, copy, me, sides);
                List<GameAction> next = new ArrayList<>(line);
                next.add(m);
                if (me.equals(copy.getWinnerId())) return next;
                if (copy.getStatus() != GameRoom.RoomStatus.IN_PROGRESS || !me.equals(copy.getCurrentTurnUserId()) || copy.getPrompt() != null) continue;
                List<GameAction> found = dfs(sandbox, copy, me, sides, next, seed, nodes, deadline);
                if (found != null) return found;
            } finally {
                sandbox.drop(copy);
            }
        }
        return null;
    }

    /** The line played on the deal of {@code seed} wins the game. */
    private static boolean wins(Sandbox sandbox, GameRoom room, String me, Map<String, BotPlayer> sides, List<GameAction> line, long seed) {
        GameRoom copy = sandbox.fork(room, me, new Random(seed));
        try {
            for (GameAction m : line) {
                if (copy.getStatus() != GameRoom.RoomStatus.IN_PROGRESS) break;
                GameStateUpdate u = sandbox.act(copy, me, m);
                if (u != null && u.getError() != null) return false;
                SequenceSearch.settle(sandbox, copy, me, sides);
            }
            return me.equals(copy.getWinnerId());
        } finally {
            sandbox.drop(copy);
        }
    }
}
