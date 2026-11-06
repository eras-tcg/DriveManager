package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.EffectEngine;
import com.drive.drive_manager.simulator.room.ActionType;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.ToDoubleBiFunction;

/**
 * leafPlay w (the user's game, 2026-11-04): a try ends at the start of the bot's next turn, before it can use what it
 * kept. There each move of its own other than an attack (a summon, a Ritual, a play, an effect) is tried once on a copy,
 * what it opens answered by the heuristic, and the try earns w times the best gain over the table as it is (never a
 * loss: it may also do nothing). So a card kept for a better play next turn (Krokler by Ritual over a creature that
 * will be in the Línea frontal by then) is worth what that play is, not one more card in the hand. Optimistic: the
 * rival does not answer that move, hence the share w. Gems paid below 0 by that move count as in the turn (gemDebt).
 */
final class LeafPlay {

    /** Moves tried at most at the leaf. */
    private static final int MAX_MOVES = 10;

    private LeafPlay() {}

    static double bonus(Sandbox sandbox, GameRoom copy, String me, BotSettings s, ToDoubleBiFunction<GameRoom, String> score, Random deal) {
        double w = s.get("leafPlay");
        if (w <= 0 || copy.getStatus() != GameRoom.RoomStatus.IN_PROGRESS || !me.equals(copy.getCurrentTurnUserId())) return 0;
        Map<String, BotPlayer> sides = Map.of(me, new HeuristicBot(s), copy.getOpponentId(me), new HeuristicBot(s));
        // The start of its turn first (its triggers, the rival's answers): the main phase is where it moves
        SequenceSearch.settle(sandbox, copy, me, sides);
        if (copy.getStatus() != GameRoom.RoomStatus.IN_PROGRESS || !"MAIN".equals(copy.getCurrentPhase()) || copy.getPrompt() != null) return 0;
        double base = score.applyAsDouble(copy, me), best = base;
        int rivalHand = copy.getPlayer(copy.getOpponentId(me)).getHand().size();
        List<GameAction> moves = LookaheadBot.mainMoves(copy, me).stream().filter(m -> !ActionType.ATTACK.is(m)).limit(MAX_MOVES).toList();
        long seed = deal != null ? deal.nextLong() : sandbox.deal();
        for (GameAction m : moves) {
            GameRoom next = sandbox.fork(copy, me, new Random(seed));
            try {
                int from = next.getTimeline().size();
                GameStateUpdate u = sandbox.act(next, me, m);
                if (u != null && u.getError() != null) continue;
                SequenceSearch.settle(sandbox, next, me, Map.of(me, new HeuristicBot(s), next.getOpponentId(me), new HeuristicBot(s)));
                double debt = Math.max(0, -EffectEngine.gemsOf(next, me));
                double used = s.get("sUse") != 0 ? s.get("sUse") * StructureUse.count(next, me, from) : 0;
                best = Math.max(best, score.applyAsDouble(next, me) - TableScore.overExtend(debt, rivalHand, s) + used);
            } finally {
                sandbox.drop(next);
            }
        }
        return w * (best - base);
    }
}
