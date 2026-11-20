package com.drive.drive_manager.simulator.bot

import com.drive.drive_manager.simulator.platform.Clocks
import kotlin.jvm.JvmStatic
import com.drive.drive_manager.simulator.engine.comparingInt
import com.drive.drive_manager.simulator.engine.frozenListOf
import com.drive.drive_manager.simulator.engine.frozenMapOf
import com.drive.drive_manager.simulator.engine.PromptPurpose
import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.engine.GameRandom

/**
 * lethalLives n (the user's st2 game, 2026-11-05): with the rival at n lives or fewer, the turn is first searched for a
 * win by the engine itself, before the look-ahead's tries: summons (normal and special: an Evolución enters the Línea
 * frontal and attacks at once), cards played and direct attacks, up to MAX_DEPTH moves in a row, each played on a copy
 * of the room (what it opens answered by the heuristic on both sides, the rival's blocks included). A line that wins
 * on its deal is played again on two other deals (the rival's hidden hand and lives dealt again: Reforzar may save a
 * life) and kept when it wins on two of the three. The tries found it too, given time; but four moves in a row
 * (evolve, attack, evolve, attack) are past what the sequences screen when the time is short, and the bot passed with
 * the game won at hand. The line joins the candidates first, so the tries judge it and, if time runs out, it stays.
 *
 * lethalBlocks (duel st2-st1 s13078, 2026-11-20: the rival at 3 lives, a line found "won" opening with Dragón
 * Carmesí, Letalidad 1 on its attack; the rival chump-blocked it with Mihel and the game went on): the heuristic that
 * answers the rival's blocks in the copies did not block that attack, so the line won on every deal and was taken as
 * a fact (10000, no floor). The blocks are on the table: the line holds only if it wins whatever the rival blocks with,
 * every answer to every block prompt tried on its own copy (only the hidden hand and lives are left to the deals).
 * A line that does not hold is no win, and the search goes on: another order (an attack that draws the block first)
 * may hold.
 */
internal object LethalSearch {

    private const val MAX_DEPTH = 6
    private const val MAX_NODES = 160
    private const val MAX_NANOS = 300_000_000L

    /** A winning sequence of moves this turn, or null (none found, or the rival has more lives than lethalLives). */
    @JvmStatic
    fun find(sandbox: Sandbox, room: GameRoom, me: String?, s: BotSettings): GameAction? {
        val lives = s.get("lethalLives").toInt()
        val rival = room.getPlayer(room.getOpponentId(me))
        if (lives <= 0 || rival == null || rival.lifeStack.size > lives || me!! != room.currentTurnUserId
                || room.prompt != null || room.status != GameRoom.RoomStatus.IN_PROGRESS) return null
        val sides = frozenMapOf<String?, BotPlayer>(me, HeuristicBot(s), room.getOpponentId(me), HeuristicBot(s))
        val deadline = Clocks.nanos() + MAX_NANOS
        val nodes = intArrayOf(0)
        val seed = sandbox.deal()
        val blocks = s.get("lethalBlocks") >= 0.5
        val line = dfs(sandbox, room, room, me, sides, ArrayList(), seed, nodes, deadline, blocks) ?: return null
        var wins = 1
        for (k in 0 until 2) if (if (blocks) holds(sandbox, room, me, sides, line, sandbox.deal()) else LethalSearch.wins(sandbox, room, me, sides, line, sandbox.deal())) wins++
        return if (wins >= 2) BotPlayer.action(SequenceSearch.SEQUENCE, frozenMapOf<String, Any?>("moves", line, AttackFloor.LETHAL, true)) else null
    }

    private fun dfs(sandbox: Sandbox, root: GameRoom, room: GameRoom, me: String, sides: Map<String?, BotPlayer>,
                    line: List<GameAction>, seed: Long, nodes: IntArray, deadline: Long, blocks: Boolean): List<GameAction>? {
        if (line.size >= MAX_DEPTH) return null
        val moves: MutableList<GameAction> = ArrayList(LookaheadBot.mainMoves(room, me).filter { m ->
            when (ActionType.of(m)) {
                ActionType.ATTACK -> m.getString("targetInstanceId") == null
                ActionType.RITUAL_SUMMON, ActionType.NORMAL_SUMMON, ActionType.PLAY_CARD -> true
                else -> false
            }
        })
        // Attacks first: a win found sooner costs fewer copies
        moves.sortWith(comparingInt { m: GameAction -> if (ActionType.ATTACK.`is`(m)) 0 else if (ActionType.RITUAL_SUMMON.`is`(m)) 1 else 2 })
        for (m in moves) {
            if (++nodes[0] > MAX_NODES || Clocks.nanos() > deadline) return null
            val copy = sandbox.fork(room, me, GameRandom(seed))
            try {
                val u = sandbox.act(copy, me, m)
                if (u != null && u.error != null) continue
                SequenceSearch.settle(sandbox, copy, me, sides)
                val next: MutableList<GameAction> = ArrayList(line)
                next.add(m)
                if (me == copy.winnerId) {
                    if (!blocks || holds(sandbox, root, me, sides, next, seed)) return next
                    continue
                }
                if (copy.status != GameRoom.RoomStatus.IN_PROGRESS || me != copy.currentTurnUserId || copy.prompt != null) continue
                val found = dfs(sandbox, root, copy, me, sides, next, seed, nodes, deadline, blocks)
                if (found != null) return found
            } finally {
                sandbox.drop(copy)
            }
        }
        return null
    }

    /**
     * lethalBlocks: the line played on the deal of `seed` wins whatever the rival blocks with. Its moves are played in
     * order and what they open answered by the heuristic, except the rival's block prompts: each answer (no block, if
     * allowed, and each creature it may block with) goes on a copy of its own and every one must end in the win.
     * Beyond [MAX_BRANCHES] copies it is not proven (false).
     */
    private fun holds(sandbox: Sandbox, room: GameRoom, me: String, sides: Map<String?, BotPlayer>, line: List<GameAction>, seed: Long): Boolean {
        val copy = sandbox.fork(room, me, GameRandom(seed))
        try {
            return branch(sandbox, copy, me, sides, line, 0, intArrayOf(0))
        } finally {
            sandbox.drop(copy)
        }
    }

    /** The rest of the line from move `at` on `copy` (owned by the caller) wins against every block. */
    private fun branch(sandbox: Sandbox, copy: GameRoom, me: String, sides: Map<String?, BotPlayer>, line: List<GameAction>, at: Int, copies: IntArray): Boolean {
        var i = at
        for (step in 0 until MAX_STEPS) {
            if (copy.status != GameRoom.RoomStatus.IN_PROGRESS) return me == copy.winnerId
            val prompt = copy.prompt
            if (prompt != null && me != prompt.playerId && PromptPurpose.BLOCK.`is`(prompt.purpose)) {
                val options: MutableList<List<String>> = ArrayList()
                if (prompt.min == 0) options.add(frozenListOf())
                for (id in prompt.validIds) options.add(frozenListOf(id!!))
                for (chosen in options) {
                    if (++copies[0] > MAX_BRANCHES) return false
                    val next = sandbox.fork(copy, me, GameRandom(seed(copy)))
                    try {
                        val u = sandbox.act(next, prompt.playerId, BotPlayer.action(ActionType.RESOLVE_PROMPT,
                                frozenMapOf<String, Any?>("promptId", prompt.id, "chosen", chosen, "order", frozenListOf<Any>())))
                        if (u != null && u.error != null) continue
                        if (!branch(sandbox, next, me, sides, line, i, copies)) return false
                    } finally {
                        sandbox.drop(next)
                    }
                }
                return true
            }
            if (prompt != null || copy.isEngineBusy()) {
                val seat = if (prompt != null) prompt.playerId else copy.currentTurnUserId
                val side = sides[seat] ?: return false
                val move = side.next(copy, seat) ?: return false
                val u = sandbox.act(copy, seat, move)
                if (u != null && u.error != null) side.refused(move, u.error) else side.played(copy, move)
                continue
            }
            if (i >= line.size || me != copy.currentTurnUserId) return false
            val u = sandbox.act(copy, me, line[i++])
            if (u != null && u.error != null) return false
        }
        return false
    }

    /** A seed for a branch's copy: its moment, so the same table deals the same. */
    private fun seed(copy: GameRoom): Long = copy.eventSeq.toLong() * 31 + copy.idSeq

    /** Copies at most to prove a line against every block, and moves played on one. */
    private const val MAX_BRANCHES = 64
    private const val MAX_STEPS = 400

    /** The line played on the deal of `seed` wins the game. */
    private fun wins(sandbox: Sandbox, room: GameRoom, me: String, sides: Map<String?, BotPlayer>, line: List<GameAction>, seed: Long): Boolean {
        val copy = sandbox.fork(room, me, GameRandom(seed))
        try {
            for (m in line) {
                if (copy.status != GameRoom.RoomStatus.IN_PROGRESS) break
                val u = sandbox.act(copy, me, m)
                if (u != null && u.error != null) return false
                SequenceSearch.settle(sandbox, copy, me, sides)
            }
            return me == copy.winnerId
        } finally {
            sandbox.drop(copy)
        }
    }
}
