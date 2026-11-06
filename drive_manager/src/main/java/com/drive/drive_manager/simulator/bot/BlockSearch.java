package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.Prompt;
import com.drive.drive_manager.simulator.room.ActionType;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The best block (hBlockBest): the defender tries every answer it has (no block, if allowed, and each creature it
 * may block with) on a copy of the room, the combat played to its end, and keeps the one that leaves its own table
 * best. In the simulations the rival then answers an attack as well as it can, not by a fixed rule, so an attack
 * that a good block would punish no longer looks free.
 */
final class BlockSearch {

    private static final ThreadLocal<Sandbox> SANDBOX = ThreadLocal.withInitial(Sandbox::new);

    private BlockSearch() {}

    /** The best answer to the block prompt for {@code me}; null when there is nothing to choose. */
    static List<String> best(GameRoom room, String me, Prompt prompt, BotSettings rules) {
        List<List<String>> options = new ArrayList<>();
        if (prompt.getMin() == 0) options.add(List.of());
        for (String id : prompt.getValidIds()) options.add(List.of(id));
        if (options.size() < 2) return null;
        Sandbox sandbox = SANDBOX.get();
        long seed = room.getEventSeq() * 31 + prompt.getId().hashCode();
        BotSettings plain = rules.with("hBlockBest=0,hUseful=0");
        List<String> best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (List<String> chosen : options) {
            GameRoom copy = sandbox.fork(room, me, new Random(seed));
            try {
                GameStateUpdate u = sandbox.act(copy, me, BotPlayer.action(ActionType.RESOLVE_PROMPT, Map.of("promptId", prompt.getId(), "chosen", chosen, "order", List.of())));
                if (u != null && u.getError() != null) continue;
                // The combat to its end (and what it triggers), answered by the plain heuristic
                SequenceSearch.settle(sandbox, copy, me, Map.of(me, new HeuristicBot(plain), copy.getOpponentId(me), new HeuristicBot(plain)));
                double s = TableScore.score(copy, me, rules);
                if (s > bestScore) {
                    bestScore = s;
                    best = chosen;
                }
            } finally {
                sandbox.drop(copy);
            }
        }
        return best;
    }
}
