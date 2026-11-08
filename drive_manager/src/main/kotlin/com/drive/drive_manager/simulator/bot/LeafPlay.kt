package com.drive.drive_manager.simulator.bot

import kotlin.math.max
import com.drive.drive_manager.simulator.engine.frozenMapOf
import com.drive.drive_manager.simulator.engine.EffectEngine
import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.engine.GameRandom

/**
 * leafPlay w (the user's game, 2026-11-04): a try ends at the start of the bot's next turn, before it can use what it
 * kept. There each move of its own other than an attack (a summon, a Ritual, a play, an effect) is tried once on a copy,
 * what it opens answered by the heuristic, and the try earns w times the best gain over the table as it is (never a
 * loss: it may also do nothing). So a card kept for a better play next turn (Krokler by Ritual over a creature that
 * will be in the Línea frontal by then) is worth what that play is, not one more card in the hand. Optimistic: the
 * rival does not answer that move, hence the share w. Gems paid below 0 by that move count as in the turn (gemDebt).
 */
internal object LeafPlay {

    /** Moves tried at most at the leaf. */
    private const val MAX_MOVES = 10

    @JvmStatic
    fun bonus(sandbox: Sandbox, copy: GameRoom, me: String?, s: BotSettings, score: TableScoring, deal: GameRandom?): Double {
        val w = s.get("leafPlay")
        if (w <= 0 || copy.status != GameRoom.RoomStatus.IN_PROGRESS || me!! != copy.currentTurnUserId) return 0.0
        val sides = frozenMapOf<String?, BotPlayer>(me, HeuristicBot(s), copy.getOpponentId(me), HeuristicBot(s))
        // The start of its turn first (its triggers, the rival's answers): the main phase is where it moves
        SequenceSearch.settle(sandbox, copy, me, sides)
        if (copy.status != GameRoom.RoomStatus.IN_PROGRESS || "MAIN" != copy.currentPhase || copy.prompt != null) return 0.0
        val base = score.applyAsDouble(copy, me)
        var best = base
        val rivalHand = copy.getPlayer(copy.getOpponentId(me))!!.hand.size
        val moves = LookaheadBot.mainMoves(copy, me).filter { m -> !ActionType.ATTACK.`is`(m) }.take(MAX_MOVES)
        val seed = deal?.nextLong() ?: sandbox.deal()
        for (m in moves) {
            val next = sandbox.fork(copy, me, GameRandom(seed))
            try {
                val from = next.timeline.size
                val u = sandbox.act(next, me, m)
                if (u != null && u.error != null) continue
                SequenceSearch.settle(sandbox, next, me, frozenMapOf<String?, BotPlayer>(me, HeuristicBot(s), next.getOpponentId(me), HeuristicBot(s)))
                val debt = max(0, -EffectEngine.gemsOf(next, me)).toDouble()
                val used = if (s.get("sUse") != 0.0) s.get("sUse") * StructureUse.count(next, me, from) else 0.0
                best = max(best, score.applyAsDouble(next, me) - TableScore.overExtend(debt, rivalHand, s) + used)
            } finally {
                sandbox.drop(next)
            }
        }
        return w * (best - base)
    }
}
