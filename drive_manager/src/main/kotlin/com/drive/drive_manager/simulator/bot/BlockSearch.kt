package com.drive.drive_manager.simulator.bot

import com.drive.drive_manager.simulator.engine.Prompt
import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.engine.GameRandom

/**
 * The best block (hBlockBest): the defender tries every answer it has (no block, if allowed, and each creature it
 * may block with) on a copy of the room, the combat played to its end, and keeps the one that leaves its own table
 * best. In the simulations the rival then answers an attack as well as it can, not by a fixed rule, so an attack
 * that a good block would punish no longer looks free.
 */
internal object BlockSearch {

    private val SANDBOX: ThreadLocal<Sandbox> = ThreadLocal.withInitial { Sandbox() }

    /** The best answer to the block prompt for `me`; null when there is nothing to choose. */
    @JvmStatic
    fun best(room: GameRoom, me: String?, prompt: Prompt, rules: BotSettings): List<String>? {
        val options: MutableList<List<String>> = ArrayList()
        if (prompt.min == 0) options.add(java.util.List.of())
        for (id in prompt.validIds) options.add(java.util.List.of(id!!))
        if (options.size < 2) return null
        val sandbox = SANDBOX.get()
        val seed = room.eventSeq * 31 + prompt.id!!.hashCode()
        val plain = rules.with("hBlockBest=0,hUseful=0")
        var best: List<String>? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (chosen in options) {
            val copy = sandbox.fork(room, me, GameRandom(seed))
            try {
                val u = sandbox.act(copy, me, BotPlayer.action(ActionType.RESOLVE_PROMPT,
                        java.util.Map.of<String, Any>("promptId", prompt.id!!, "chosen", chosen, "order", java.util.List.of<Any>())))
                if (u != null && u.error != null) continue
                // The combat to its end (and what it triggers), answered by the plain heuristic
                SequenceSearch.settle(sandbox, copy, me, java.util.Map.of<String?, BotPlayer>(me, HeuristicBot(plain), copy.getOpponentId(me), HeuristicBot(plain)))
                val s = TableScore.score(copy, me, rules)
                if (s > bestScore) {
                    bestScore = s
                    best = chosen
                }
            } finally {
                sandbox.drop(copy)
            }
        }
        return best
    }
}
