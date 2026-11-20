package com.drive.drive_manager.simulator.bot

import kotlin.jvm.JvmStatic
import com.drive.drive_manager.simulator.platform.LocalValue
import com.drive.drive_manager.simulator.engine.frozenListOf
import com.drive.drive_manager.simulator.engine.frozenMapOf
import com.drive.drive_manager.simulator.engine.Prompt
import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.engine.GameRandom

/**
 * The best block (hBlockBest): the defender tries every answer it has (no block, if allowed, and each creature it
 * may block with) on a copy of the room, the combat played to its end, and keeps the one that leaves its own table
 * best. In the simulations the rival then answers an attack as well as it can, not by a fixed rule, so an attack
 * that a good block would punish no longer looks free.
 *
 * hBlockBest 2 (duel st2-st1 s13078, 2026-11-20: at 3 lives the rival in the tries let Dragón Carmesí with Letalidad
 * through, the real one chump-blocked it with Mihel, and the bot expected 7000 to 9000 for a turn that was not won):
 * each answer is judged after the rest of the attacker's turn, played by the heuristic, not after this combat alone.
 * A hit that only kills together with the next attack is then seen, and so is a chump that saves the game.
 */
internal object BlockSearch {

    private val SANDBOX: LocalValue<Sandbox> = LocalValue { Sandbox() }

    /** The best answer to the block prompt for `me`; null when there is nothing to choose. */
    @JvmStatic
    fun best(room: GameRoom, me: String?, prompt: Prompt, rules: BotSettings): List<String>? {
        val options: MutableList<List<String>> = ArrayList()
        if (prompt.min == 0) options.add(frozenListOf())
        for (id in prompt.validIds) options.add(frozenListOf(id!!))
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
                        frozenMapOf<String, Any>("promptId", prompt.id!!, "chosen", chosen, "order", frozenListOf<Any>())))
                if (u != null && u.error != null) continue
                // The combat to its end (and what it triggers), answered by the plain heuristic
                val sides = frozenMapOf<String?, BotPlayer>(me, HeuristicBot(plain), copy.getOpponentId(me), HeuristicBot(plain))
                SequenceSearch.settle(sandbox, copy, me, sides)
                // hBlockBest 2: and the rest of the attacker's turn
                if (rules.get("hBlockBest") >= 1.5) restOfTurn(sandbox, copy, sides)
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

    /** Plays the turn on `copy` to its end (or the game's), both sides by their heuristics. */
    private fun restOfTurn(sandbox: Sandbox, copy: GameRoom, sides: Map<String?, BotPlayer>) {
        val turn = copy.currentTurnUserId
        for (step in 0 until MAX_STEPS) {
            if (copy.status != GameRoom.RoomStatus.IN_PROGRESS || turn != copy.currentTurnUserId) return
            val seat = if (copy.prompt != null) copy.prompt!!.playerId else copy.currentTurnUserId
            val side = sides[seat] ?: return
            val next = side.next(copy, seat) ?: return
            val u = sandbox.act(copy, seat, next)
            if (u != null && u.error != null) side.refused(next, u.error) else side.played(copy, next)
        }
    }

    /** Moves played at most for the rest of a turn. */
    private const val MAX_STEPS = 200
}
