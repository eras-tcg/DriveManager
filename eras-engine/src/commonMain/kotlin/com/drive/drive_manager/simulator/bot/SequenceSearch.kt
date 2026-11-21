package com.drive.drive_manager.simulator.bot

import com.drive.drive_manager.simulator.platform.Clocks
import kotlin.jvm.JvmStatic
import kotlin.math.max
import kotlin.math.min
import com.drive.drive_manager.simulator.engine.valueOr
import com.drive.drive_manager.simulator.engine.comparingDouble
import com.drive.drive_manager.simulator.engine.comparingInt
import com.drive.drive_manager.simulator.engine.frozenListOf
import com.drive.drive_manager.simulator.engine.frozenMapOf
import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.engine.GameRandom

/**
 * Sequences of the bot's own moves within its turn, so a combo is found by search and not left to the heuristic that
 * finishes the turn in the copies (its fixed order rarely plays a combo's second step). Off unless `seqDepth` is 2
 * or 3. Each sequence is applied on a copy of the room (the prompts its moves open answered by the heuristic) and
 * screened at once by the table's score, with no rollout, plus `seqTriggers` per effect of its own it activated
 * on the way (the engine's own record of what fired: no card is named). It grows one move at a time keeping the best
 * `seqKeep`; those join the candidates as one move each, and the rollouts play the whole sequence before the
 * heuristic takes over. The bot plays a sequence's first move and thinks again at its next decision. Screening stops at
 * `seqShare` of the decision's time budget (a quarter). With `seqRollout` each sequence is screened by one
 * try instead (the turn and the rival's played by the heuristic, as the candidates are judged), on the same deal.
 */
internal object SequenceSearch {

    /** A candidate that is several moves in a row ("moves": the list). Never sent to the engine. */
    const val SEQUENCE = "SEQUENCE"
    /** Moves played at most to answer what a sequence's move opens before the next one. */
    private const val MAX_SETTLE = 200

    @JvmStatic
    fun of(moves: List<GameAction>): GameAction {
        return BotPlayer.action(SEQUENCE, frozenMapOf<String, Any?>("moves", moves))
    }

    /**
     * cascade: every direct attack it can make, the strongest attacker first, as one candidate. Played in full in the
     * tries (blocks, answers and lives that were not removed included); an attack no longer possible is skipped and the
     * game won ends it. So the search sees a win, or good trades, that the heuristic finishing the turn never plays
     * (it does not attack into blockers). The bot makes the first attack and thinks again after it. Null with fewer
     * than 2 attackers.
     */
    @JvmStatic
    fun cascade(room: GameRoom, me: String?): GameAction? {
        val self = room.getPlayer(me) ?: return null
        @Suppress("UNCHECKED_CAST")
        val legal = com.drive.drive_manager.simulator.engine.LegalActions.of(room, me) as Map<String?, List<String>>
        val attacks = self.field.filter { c -> legal.valueOr(c.instanceId, frozenListOf()).contains("attack") }
                .sortedWith(comparingInt { c: CardSlot -> com.drive.drive_manager.simulator.engine.EffectEngine.strength(c) }.reversed())
                .map { c -> BotPlayer.action(ActionType.ATTACK, frozenMapOf<String, Any?>("instanceId", c.instanceId)) }
        return if (attacks.size < 2) null else BotPlayer.action(SEQUENCE, frozenMapOf<String, Any?>("moves", attacks, "soft", true))
    }

    /** A sequence whose later moves are skipped when no longer possible (the cascade), not one that fails with them. */
    @JvmStatic
    fun soft(a: GameAction?): Boolean {
        return a != null && SEQUENCE == a.type && a.payload!!["soft"] == true
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun moves(a: GameAction?): List<GameAction> {
        return if (a != null && SEQUENCE == a.type) a.payload!!["moves"] as List<GameAction> else frozenListOf(a!!)
    }

    /** The move to play now: a sequence's first, or the move itself. */
    @JvmStatic
    fun first(a: GameAction?): GameAction? {
        return if (a == null) null else moves(a)[0]
    }

    private class Screened(val moves: List<GameAction>, val score: Double, val next: List<GameAction>)

    /** The best sequences of 2 or more moves starting with one of `firsts`, as candidates (empty when off). */
    @JvmStatic
    fun best(sandbox: Sandbox, room: GameRoom, me: String?, firsts: List<GameAction>, s: BotSettings): List<GameAction> {
        val depth = s.get("seqDepth").toInt()
        val keep = max(1, s.get("seqKeep").toInt())
        val cascade = if (s.get("cascade") >= 0.5) cascade(room, me) else null
        // lethalLives: a win found by the engine first (LethalSearch), ahead of every sequence
        val lethal = LethalSearch.find(sandbox, room, me, s)
        if (depth < 2) {
            val few: MutableList<GameAction> = ArrayList()
            if (lethal != null) few.add(lethal)
            if (cascade != null) few.add(cascade)
            return few
        }
        val deadline = Clocks.nanos() + (s.budgetMs() * 1_000_000L * s.get("seqShare")).toLong()
        val seed = sandbox.deal()
        val triggers = s.get("seqTriggers")
        // Level 1: every first move; then each level extends the best ones by every move legal after them
        var level: MutableList<Screened> = ArrayList()
        for (m in firsts) {
            if (ActionType.END_TURN.`is`(m) || SEQUENCE == m.type) continue
            val x = screen(sandbox, room, me, frozenListOf(m), seed, s, triggers)
            if (x != null) level.add(x)
        }
        val singles: MutableList<Screened> = ArrayList(level)
        val found: MutableList<Screened> = ArrayList()
        var d = 2
        while (d <= depth && Clocks.nanos() < deadline) {
            level.sortWith(comparingDouble { x: Screened -> -x.score })
            val grown: MutableList<Screened> = ArrayList()
            for (x in level.subList(0, min(level.size, if (d == 2) level.size else keep))) {
                for (m in x.next) {
                    if (Clocks.nanos() > deadline) break
                    val seq: MutableList<GameAction> = ArrayList(x.moves)
                    seq.add(m)
                    val y = screen(sandbox, room, me, seq, seed, s, triggers)
                    if (y != null) grown.add(y)
                }
            }
            found.addAll(grown)
            level = grown
            d++
        }
        val attacking = if (s.get("seqAttack") < 0.5) frozenListOf() else attacksFirst(sandbox, room, me, firsts, singles, found, seed, s, triggers)
                .sortedWith(comparingDouble { x: Screened -> -x.score }).take(ATTACK_KEEP)
        found.sortWith(comparingDouble { x: Screened -> -x.score })
        val out: MutableList<GameAction> = ArrayList(found.take(keep).map { x -> of(x.moves) })
        if (s.get("seqPass") >= 0.5) {
            // seqPass: the best single moves and sequences, then the turn passed: what it keeps in the hand stays there in
            // the tries (the heuristic finishing the turn plays all it can), so keeping a card is a candidate too
            singles.sortWith(comparingDouble { x: Screened -> -x.score })
            val bases: MutableList<List<GameAction>> = ArrayList()
            singles.take(keep).forEach { x -> bases.add(x.moves) }
            found.take(keep).forEach { x -> bases.add(x.moves) }
            for (b in bases) {
                val seq: MutableList<GameAction> = ArrayList(b)
                seq.add(BotPlayer.action(ActionType.END_TURN, frozenMapOf()))
                out.add(of(seq))
            }
        }
        // seqAttack: the best of them go to the tries whatever their screening (one try on one deal ranked them below
        // sequences the tries then scored 20 to 30 lower: the tries, not the screening, weigh the attack)
        for (x in attacking) if (out.none { a -> moves(a) == x.moves }) out.add(of(x.moves))
        if (cascade != null) out.add(0, cascade)
        if (lethal != null) out.add(0, lethal)
        return out
    }

    /**
     * seqAttack (duel st4-st2 s13047, 2026-11-20: Duregón 2 against a rival with no creature in its Línea frontal; it
     * summoned Fadrake and did not attack): an attack needs 0 or more gems, so it goes before what spends them, and once
     * the turn's plan is played it is no longer possible. Tried alone, an attack is judged with the heuristic finishing
     * the turn (it did not summon Fadrake after it: −27, as much as passing), and the sequences grow from the best
     * single moves first, so the time ran out before "attack → the plan" was screened: the attack was never weighed
     * together with the turn's development. Here each attack is put before the best plans found without one (the best
     * sequence and the two best single moves) and screened; the best [ATTACK_KEEP] go to the tries, which choose: no
     * order is imposed. At most [ATTACK_FIRST] screens, past the sequences' deadline.
     */
    private fun attacksFirst(sandbox: Sandbox, room: GameRoom, me: String?, firsts: List<GameAction>, singles: List<Screened>, found: List<Screened>,
                             seed: Long, s: BotSettings, triggers: Double): List<Screened> {
        val attacks = firsts.filter { m -> ActionType.ATTACK.`is`(m) }
        if (attacks.isEmpty()) return frozenListOf()
        val plain = { x: Screened -> x.moves.none { m -> ActionType.ATTACK.`is`(m) || ActionType.END_TURN.`is`(m) } }
        val plans: MutableList<List<GameAction>> = ArrayList()
        found.filter(plain).maxByOrNull { x -> x.score }?.let { x -> plans.add(x.moves) }
        singles.filter(plain).sortedWith(comparingDouble { x: Screened -> -x.score }).take(2)
                .forEach { x -> if (plans.none { p -> p == x.moves }) plans.add(x.moves) }
        val out: MutableList<Screened> = ArrayList()
        var n = 0
        for (plan in plans) for (a in attacks) {
            if (n++ >= ATTACK_FIRST) return out
            val seq: MutableList<GameAction> = ArrayList()
            seq.add(a)
            seq.addAll(plan)
            val y = screen(sandbox, room, me, seq, seed, s, triggers)
            if (y != null) out.add(y)
        }
        return out
    }

    /** Screens at most of an attack put before the best plans (seqAttack), and how many of them go to the tries. */
    private const val ATTACK_FIRST = 12
    private const val ATTACK_KEEP = 2

    /** The sequence on a copy, scored right away, with the moves legal after it (null: a move was refused). */
    private fun screen(sandbox: Sandbox, room: GameRoom, me: String?, moves: List<GameAction>, seed: Long, s: BotSettings, triggers: Double): Screened? {
        val copy = sandbox.fork(room, me, GameRandom(seed))
        try {
            val before = copy.timeline.size
            val sides = frozenMapOf<String?, BotPlayer>(me, HeuristicBot(s), copy.getOpponentId(me), HeuristicBot(s))
            for (m in moves) {
                val u = sandbox.act(copy, me, m)
                if (u != null && u.error != null) return null
                settle(sandbox, copy, me, sides)
            }
            val fired = copy.timeline.subList(before, copy.timeline.size)
                    .count { e -> com.drive.drive_manager.simulator.engine.GameEvent.EFFECT_ACTIVATED == e.type && me!! == e.playerId }.toLong()
            val mine = copy.status == GameRoom.RoomStatus.IN_PROGRESS && me!! == copy.currentTurnUserId && !copy.isEngineBusy()
            // hUseful: no utility that would do nothing in a sequence either (its activation would earn the trigger bonus)
            val next: List<GameAction> = if (!mine) frozenListOf() else LookaheadBot.mainMoves(copy, me)
                    .filter { m -> s.get("hUseful") < 0.5 || Usefulness.playDoesSomething(copy, me, m, s) }
            // Gems paid below 0 go to the rival for their turn
            val debt = max(0, -com.drive.drive_manager.simulator.engine.EffectEngine.gemsOf(copy, me)).toDouble()
            // seqRollout: screened by one try (the rest of the turn and the rival's by the heuristic) instead of the table now
            val table = if (s.get("seqRollout") >= 0.5)
                MoveTrials(s, sandbox, TableScoring { r: GameRoom, p: String? -> TableScore.score(r, p, s) }, Long.MAX_VALUE)
                        .once(room, me, if (moves.size == 1) moves[0] else of(moves), seed)
            else TableScore.score(copy, me, s) - TableScore.overExtend(debt, copy.getPlayer(copy.getOpponentId(me))!!.hand.size, s)
            return Screened(moves, table + triggers * fired, next)
        } finally {
            sandbox.drop(copy)
        }
    }

    /** Answers by the heuristic what is pending (prompts, chains, the rival's blocks) until the bot may move again. */
    @JvmStatic
    fun settle(sandbox: Sandbox, copy: GameRoom, me: String?, sides: Map<String?, BotPlayer>) {
        for (step in 0 until MAX_SETTLE) {
            if (!(copy.status == GameRoom.RoomStatus.IN_PROGRESS && (copy.prompt != null || copy.isEngineBusy()))) break
            val seat = if (copy.prompt != null) copy.prompt!!.playerId else copy.currentTurnUserId
            val side = sides[seat]
            val next = (if (side == null) null else side.next(copy, seat)) ?: return
            val u = sandbox.act(copy, seat, next)
            if (u != null && u.error != null) side!!.refused(next, u.error)
            else side!!.played(copy, next)
        }
    }
}
