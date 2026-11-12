package com.drive.drive_manager.simulator.bot

import com.drive.drive_manager.simulator.platform.Clocks
import kotlin.jvm.JvmStatic
import com.drive.drive_manager.simulator.engine.comparingInt
import com.drive.drive_manager.simulator.engine.frozenMapOf
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
        val line = dfs(sandbox, room, me, sides, ArrayList(), seed, nodes, deadline) ?: return null
        var wins = 1
        for (k in 0 until 2) if (LethalSearch.wins(sandbox, room, me, sides, line, sandbox.deal())) wins++
        return if (wins >= 2) BotPlayer.action(SequenceSearch.SEQUENCE, frozenMapOf<String, Any?>("moves", line, AttackFloor.LETHAL, true)) else null
    }

    private fun dfs(sandbox: Sandbox, room: GameRoom, me: String, sides: Map<String?, BotPlayer>,
                    line: List<GameAction>, seed: Long, nodes: IntArray, deadline: Long): List<GameAction>? {
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
                if (me == copy.winnerId) return next
                if (copy.status != GameRoom.RoomStatus.IN_PROGRESS || me != copy.currentTurnUserId || copy.prompt != null) continue
                val found = dfs(sandbox, copy, me, sides, next, seed, nodes, deadline)
                if (found != null) return found
            } finally {
                sandbox.drop(copy)
            }
        }
        return null
    }

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
