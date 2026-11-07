package com.drive.drive_manager.simulator.bot

import com.drive.drive_manager.simulator.engine.GameEvent
import com.drive.drive_manager.simulator.engine.LegalActions
import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom

/**
 * planKeep b (the user's call, 2026-11-04): the bot keeps to the plan it chose. It chooses among single moves and
 * sequences, makes the first move and thinks again; the tries are noisy, so at the next decision another plan may win
 * by luck and the first one is dropped halfway (a bait attack made and its payoff never played, a card kept for later
 * summoned anyway). So the rest of the chosen sequence is remembered, and at the next decision of the same turn, while
 * its next move is still allowed, it is a candidate again, first (it wins the ties) and with b more on every try: another
 * plan replaces it only when it is better by more than that. What happened in between (a block, an answer of the rival,
 * a life that was not removed) is in the tries of the rest as of any other candidate. A pass at the plan's end is not
 * kept (seqPass): passing is weighed afresh against what the moves made opened.
 */
internal class PlanKeep {

    /** The rest of the plan, and the turn it is of (who plays it and when that turn began). */
    private var rest: List<GameAction> = java.util.List.of()
    private var soft = false
    private var turn: String? = null

    /** The rest of the plan as the first candidate, with its bonus (nothing when off, done, of another turn or not allowed now). */
    fun offer(room: GameRoom, me: String?, candidates: MutableList<GameAction>, s: BotSettings) {
        val bonus = s.get("planKeep")
        if (bonus <= 0 || rest.isEmpty() || turnOf(room) != turn || !allowed(room, me, rest[0])) return
        val payload: MutableMap<String, Any> = HashMap()
        payload["moves"] = rest
        payload["bonus"] = bonus
        if (soft) payload["soft"] = true
        candidates.add(0, BotPlayer.action(SequenceSearch.SEQUENCE, payload))
    }

    /** The move to make now (AttackFirst's choice from the chosen one), remembering the rest of the chosen sequence. */
    fun next(sandbox: Sandbox, room: GameRoom, me: String?, chosen: GameAction?, s: BotSettings): GameAction? {
        val move = AttackFirst.first(sandbox, room, me, chosen, s)
        rest = java.util.List.of()
        turn = null
        // A cascade is not kept: it is worth it only while the win or its trades are still there (a block the tries did not
        // expect breaks it), and it is offered afresh at every decision anyway
        if (s.get("planKeep") <= 0 || chosen == null || move == null || ActionType.END_TURN.`is`(move) || SequenceSearch.soft(chosen)) return move
        val moves: MutableList<GameAction> = ArrayList(SequenceSearch.moves(chosen))
        for (i in 0 until moves.size) if (moves[i] === move) { moves.removeAt(i); break }
        // A plan's passing at its end is not kept: what it opens (a free card, an ally untapped) is weighed afresh
        while (!moves.isEmpty() && ActionType.END_TURN.`is`(moves[moves.size - 1])) moves.removeAt(moves.size - 1)
        if (!moves.isEmpty()) {
            rest = java.util.List.copyOf(moves)
            soft = SequenceSearch.soft(chosen)
            turn = turnOf(room)
        }
        return move
    }

    companion object {
        /** Whose turn it is and when it began (the last start of a turn in the timeline). */
        private fun turnOf(room: GameRoom): String {
            val tl = room.timeline
            var began: Long = -1
            for (i in tl.size - 1 downTo 0) if (GameEvent.TURN_START == tl[i].type) { began = i.toLong(); break }
            return room.currentTurnUserId + ":" + began
        }

        /** The move is allowed now, by the rules' list of what each card may do. */
        @Suppress("UNCHECKED_CAST")
        private fun allowed(room: GameRoom, me: String?, m: GameAction): Boolean {
            val type = ActionType.of(m)
            if (type == ActionType.END_TURN) return true
            val id = m.getString("instanceId")
            val can: List<String> = if (id == null) java.util.List.of() else LegalActions.of(room, me).getOrDefault(id, java.util.List.of())
            return when (type) {
                ActionType.ATTACK -> can.contains("attack") && (m.getString("targetInstanceId") == null
                        || (LegalActions.attackTargets(room, me) as Map<String?, List<String>>).getOrDefault(id, java.util.List.of())
                            .contains(m.getString("targetInstanceId")))
                ActionType.NORMAL_SUMMON -> can.contains("summon")
                ActionType.PLAY_CARD -> can.contains("play")
                ActionType.RITUAL_SUMMON -> can.contains("ritual")
                ActionType.ACTIVATE_EFFECT -> can.contains("activate")
                else -> false
            }
        }
    }
}
