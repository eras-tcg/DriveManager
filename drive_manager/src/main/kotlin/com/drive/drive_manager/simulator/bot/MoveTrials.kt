package com.drive.drive_manager.simulator.bot

import com.drive.drive_manager.simulator.engine.EffectEngine
import com.drive.drive_manager.simulator.engine.GameEvent
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.GameStateUpdate
import com.drive.drive_manager.simulator.room.PlayerState
import java.util.Random
import java.util.function.ToDoubleBiFunction

/**
 * One decision of the look-ahead bot: its tries of the candidate moves, until the best is found or time runs out. A
 * try plays the move on a copy of the room and then both sides by the heuristic until the turn passes
 * ([BotSettings.turns] times), and scores the table left. How the tries are shared out and dealt is in
 * the settings: `samples` for each move, or by successive halving; each on a deal of its own, or the k-th try of
 * every move on the same k-th deal.
 */
internal class MoveTrials(
    private val settings: BotSettings,
    private val sandbox: Sandbox,
    private val score: ToDoubleBiFunction<GameRoom, String?>,
    private val deadline: Long,
) {

    /** The seeds of this decision's deals, drawn as they are needed (only with sameDeals). */
    private val deals: MutableList<Long> = ArrayList()
    var ranOutOfTime = false
    var movesTried = 0

    /** The mean score of the tries of the move chosen: what the bot expects the table to be worth. */
    var expected = 0.0

    /** The candidate with the best score (the first one, the heuristic's, on a tie or when time runs out). */
    fun best(room: GameRoom, me: String?, candidates: List<GameAction>): GameAction {
        return if (settings.halving()) halving(room, me, candidates) else everyMove(room, me, candidates)
    }

    /** Each move tried `samples` times, in order, until time runs out. */
    private fun everyMove(room: GameRoom, me: String?, candidates: List<GameAction>): GameAction {
        val n = candidates.size
        val sum = DoubleArray(n)
        val tries = IntArray(n)
        for (i in 0 until n) {
            if (i > 0 && System.nanoTime() > deadline) {
                ranOutOfTime = true
                break
            }
            for (k in 0 until settings.samples()) sum[i] += tryMove(room, me, candidates[i], deal(k), true)
            tries[i] = settings.samples()
            movesTried++
        }
        return pick(candidates, sum, tries, bestMean(sum, tries))
    }

    /**
     * Successive halving: every move gets a first try, then the better half (by mean score) go on to more tries, round
     * after round, until one is left. Each round spends about the same, so the finalists get most of the tries; in all
     * it spends what `samples` tries of each move would. Each new deal is played by every move still in, so when
     * time runs out they have had (almost) the same tries; then the best mean so far wins.
     */
    private fun halving(room: GameRoom, me: String?, candidates: List<GameAction>): GameAction {
        val n = candidates.size
        val rounds = Math.max(1, 32 - Integer.numberOfLeadingZeros(n - 1))
        val perRound = Math.max(1, settings.samples() * n / rounds)
        val sum = DoubleArray(n)
        val tries = IntArray(n)
        var alive: MutableList<Int> = ArrayList()
        for (i in 0 until n) alive.add(i)
        val droppedIn = IntArray(n)
        var round = 0
        var dealt = 0
        while (alive.size > 1) {
            // firstTries: at least this many tries for every move in the first round (one bad deal does not drop a good move)
            val more = Math.max(if (dealt == 0) Math.max(1, settings.get("firstTries").toInt()) else 1, perRound / alive.size)
            // innerFinal: the rest of its own turn thought (innerCands) only once this few moves are left: the cheap
            // tries sort the field, the deep ones decide among the finalists (each finalist gets the same tries)
            val deep = settings.get("innerFinal") < 1 || alive.size <= settings.get("innerFinal")
            for (k in dealt until dealt + more) {
                for (i in alive) {
                    if ((k > 0 || i > 0) && System.nanoTime() > deadline) {
                        ranOutOfTime = true
                        return pick(candidates, sum, tries, bestMean(sum, tries))
                    }
                    sum[i] += tryMove(room, me, candidates[i], deal(k), deep)
                    tries[i]++
                    if (k == 0) movesTried++
                }
            }
            dealt += more
            round++
            // Stable: on equal means the earlier move (the heuristic's first) stays ahead
            alive.sortWith(Comparator.comparingDouble { i: Int -> -sum[i] / tries[i] })
            for (i in alive.subList((alive.size + 1) / 2, alive.size)) droppedIn[i] = round
            alive = ArrayList(alive.subList(0, (alive.size + 1) / 2))
        }
        if (AUDIT.get() != null) audit(room, candidates, sum, tries, droppedIn, alive[0])
        return pick(candidates, sum, tries, alive[0])
    }

    /** The move to play, with what the bot expects from it. */
    private fun pick(candidates: List<GameAction>, sum: DoubleArray, tries: IntArray, best: Int): GameAction {
        expected = sum[best] / tries[best]
        return candidates[best]
    }

    /** The k-th deal of this decision when every move is tried on the same deals; else null (the sandbox's own chance). */
    private fun deal(k: Int): Random? {
        if (!settings.sameDeals()) return null
        while (deals.size <= k) deals.add(sandbox.deal())
        return Random(deals[k])
    }

    /** The small look-ahead that plays the rest of the bot's own turn in a try (innerCands, innerMs). */
    private fun inner(deal: Random?): BotPlayer {
        val bot = LookaheadBot(settings.with("turns=1,samples=1,halving=0,seqDepth=0,innerCands=0,maxCandidates="
                + settings.get("innerCands").toInt() + ",budgetMs=" + settings.get("innerMs").toInt()))
        if (deal != null) bot.seed(deal.nextLong())
        return bot
    }

    /** One try of the move on the deal of `seed` (the sequence search screens by it with seqRollout). */
    fun once(room: GameRoom, me: String?, move: GameAction, seed: Long): Double {
        return tryMove(room, me, move, Random(seed), false)
    }

    /** Plays the move on a copy and then both sides by the heuristic until the turn passes; the score of the table left. */
    private fun tryMove(room: GameRoom, me: String?, move: GameAction, deal: Random?, deep: Boolean): Double {
        val copy = sandbox.fork(room, me, deal)
        // searchHope: in this try its searches of the deck's top find the best valid cards when they find any
        if (settings.get("searchHope") >= 0.5) copy.searchHope = me
        val start = copy.timeline.size
        try {
            var turn = copy.currentTurnUserId
            var passed = 0
            // Both sides of the copy play by the bot's own rules
            val sides: Map<String?, BotPlayer> = java.util.Map.of<String?, BotPlayer>(me, HeuristicBot(settings), copy.getOpponentId(me), HeuristicBot(settings))
            // innerCands: the rest of its own turn decided by a small look-ahead (a few candidates, one try each, to the
            // end of the turn) instead of the heuristic; the rival's turn too with innerRival
            val thinker: BotPlayer? = if (!deep || settings.get("innerCands") < 1) null else inner(deal)
            // innerRival (the user's call, 2026-11-05): the rival's own turn in the try decided by the same small look-ahead
            // as its own (innerCands, in the finalists' tries): the rival it expects is no longer the plain heuristic
            val rivalThinker: BotPlayer? = if (thinker == null || settings.get("innerRival") < 0.5) null else inner(deal)
            // A sequence's moves one after the other, what each opens answered before the next
            val moves = SequenceSearch.moves(move)
            // The fewest gems it had during its own turn: below 0 they went to the rival for their turn (gemDebt)
            var fewest = Int.MAX_VALUE
            // overExtendRival: the gems it pays in the rival's turn (an effect's summon, a life played) are the rival's at
            // once, in the turn they play (the Contador is shared): they count as gems below 0 too
            var paidInTheirs = 0
            val rivalHand = if (copy.getPlayer(copy.getOpponentId(me)) == null) 0 else copy.getPlayer(copy.getOpponentId(me))!!.hand.size
            // A cascade (soft) skips the attacks no longer possible and stops when the game or its turn is over
            val soft = SequenceSearch.soft(move)
            for (i in moves.indices) {
                if (i > 0) SequenceSearch.settle(sandbox, copy, me, sides)
                if (soft && i > 0 && (copy.status != GameRoom.RoomStatus.IN_PROGRESS || me!! != copy.currentTurnUserId)) break
                val before = EffectEngine.gemsOf(copy, me)
                val theirs = me!! != copy.currentTurnUserId
                val first: GameStateUpdate? = sandbox.act(copy, me, moves[i])
                if (first != null && first.error != null) {
                    if (soft && i > 0) continue
                    return Double.NEGATIVE_INFINITY
                }
                if (theirs) paidInTheirs += Math.max(0, before - EffectEngine.gemsOf(copy, me))
                if (me == copy.currentTurnUserId) fewest = Math.min(fewest, EffectEngine.gemsOf(copy, me))
            }
            // frontRisk: the exposure to the rival's removal is taken when its turn begins (RemovalRisk), not at the end
            // of the try: by then its own turn has begun and everything moved up again
            var atRisk: Map<String?, Double>? = null
            for (step in 0 until MAX_STEPS * settings.turns()) {
                if (copy.status != GameRoom.RoomStatus.IN_PROGRESS) break
                if (turn!! != copy.currentTurnUserId) {
                    if (me!! == turn && (atRisk == null || atRisk.isEmpty())) atRisk = RemovalRisk.risks(copy, copy.getPlayer(me), copy.getPlayer(copy.getOpponentId(me)), settings)
                    turn = copy.currentTurnUserId
                    if (++passed >= settings.turns()) break
                }
                val seat = if (copy.prompt != null) copy.prompt!!.playerId else copy.currentTurnUserId
                val side: BotPlayer? = if (thinker != null && passed == 0 && me!! == seat) thinker
                    else if (rivalThinker != null && passed == 1 && me!! != seat && seat!! == copy.currentTurnUserId) rivalThinker else sides[seat]
                val next = side?.next(copy, seat) ?: break
                val before = EffectEngine.gemsOf(copy, me)
                val theirs = me!! == seat && me != copy.currentTurnUserId
                val update = sandbox.act(copy, seat, next)
                if (theirs) paidInTheirs += Math.max(0, before - EffectEngine.gemsOf(copy, me))
                if (update != null && update.error != null) side.refused(next, update.error)
                else side.played(copy, next)
                if (me == copy.currentTurnUserId) fewest = Math.min(fewest, EffectEngine.gemsOf(copy, me))
            }
            // winFlat (duel st1-st2 s13395, 2026-11-06): a game won or lost in the try is worth that alone, without what the
            // try added (structures used, the plan's bonus): else winning later, after more moves (a deck emptied, three
            // Volcanes used), outscored the win at hand
            if (settings.get("winFlat") >= 0.5 && copy.status != GameRoom.RoomStatus.IN_PROGRESS && copy.winnerId != null) return score.applyAsDouble(copy, me)
            val debt: Double = ((if (fewest == Int.MAX_VALUE) 0 else Math.max(0, -fewest)) + (if (settings.get("overExtendRival") >= 0.5) paidInTheirs else 0)).toDouble()
            // rollTriggers: per effect of its own activated in the rest of its turn (searches, draws, summons by effect:
            // what a combo earns and the table at the end of the try barely shows)
            var fired = 0.0
            if (settings.get("rollTriggers") != 0.0) {
                for (i in start until copy.timeline.size) {
                    val e = copy.timeline[i]
                    if (GameEvent.TURN_END == e.type) break
                    if (GameEvent.EFFECT_ACTIVATED == e.type && me!! == e.playerId) fired++
                }
            }
            val lives = if (settings.get("lifeExp") >= 0.5) LifeHits.correction(copy, me, start, settings) else 0.0
            // sUse: what its structures yielded in the try (StructureUse)
            val used = if (settings.get("sUse") != 0.0) settings.get("sUse") * StructureUse.count(copy, me, start) else 0.0
            val leaf = LeafPlay.bonus(sandbox, copy, me, settings, score, deal)
            // planKeep: the rest of the plan it chose earlier is worth its bonus more (another plan must beat it by that)
            val b = move.payload!!["bonus"]
            val kept = if (b is Number) b.toDouble() else 0.0
            val exposed = RemovalRisk.charge(atRisk, copy.getPlayer(me), settings)
            return score.applyAsDouble(copy, me) - TableScore.overExtend(debt, rivalHand, settings) + settings.get("rollTriggers") * fired + lives + leaf + kept + used - exposed
        } finally {
            sandbox.drop(copy)
        }
    }

    companion object {

        /** Moves played at most on a copy per turn looked at (a turn rarely takes more than a few dozen). */
        const val MAX_STEPS = 300

        /**
         * Audit (tests only): when set for the thread, each decision by successive halving writes here every candidate with
         * its mean score, its tries and the round it was dropped in (0: it won), the chosen one first.
         */
        @JvmField
        val AUDIT: ThreadLocal<MutableList<String>> = ThreadLocal()

        private fun audit(room: GameRoom, candidates: List<GameAction>, sum: DoubleArray, tries: IntArray, droppedIn: IntArray, chosen: Int) {
            val out = AUDIT.get()
            val order: MutableList<Int> = ArrayList()
            for (i in candidates.indices) order.add(i)
            order.sortWith(Comparator.comparingInt { i: Int -> if (i == chosen) -1 else if (droppedIn[i] == 0) 0 else -droppedIn[i] + 1000 })
            for (i in order) {
                out.add(java.lang.String.format(java.util.Locale.ROOT, "%s%-60s media %8.1f  pruebas %2d  %s", if (i == chosen) "ELEGIDA " else "        ",
                        describe(room, candidates[i]), if (tries[i] == 0) Double.NaN else sum[i] / tries[i], tries[i],
                        if (i == chosen) "" else if (droppedIn[i] == 0) "finalista" else "cayó en la ronda " + droppedIn[i]))
            }
        }

        /** A candidate in words: its type and its card's name (and target's); a sequence, its moves. */
        @JvmStatic
        fun describe(room: GameRoom, a: GameAction): String {
            if (SequenceSearch.SEQUENCE == a.type) {
                return "cadena: " + java.lang.String.join(" → ", SequenceSearch.moves(a).map { m -> describe(room, m) })
            }
            val id = a.getString("instanceId")
            val target = a.getString("targetInstanceId")
            return a.type + (if (id == null) "" else " " + name(room, id)) + (if (target == null) "" else " → " + name(room, target))
        }

        private fun name(room: GameRoom, id: String): String? {
            for (p in room.players.values)
                for (z in java.util.List.of<List<CardSlot>>(p.hand, p.field, p.tributeZone, p.lifeStack, p.discardPile))
                    for (c in z) if (id == c.instanceId) return c.cardName
            return id
        }

        /**
         * The candidates the tries go to: the first maxCandidates as listed; with rankCands, when there are more, the
         * heuristic's move first and then the best by the table right after each move (its prompts answered by the
         * heuristic), so a good move listed late (an attack on a given enemy) is not cut.
         */
        @JvmStatic
        fun top(sandbox: Sandbox, room: GameRoom, me: String?, candidates: List<GameAction>, settings: BotSettings): List<GameAction> {
            val max = settings.maxCandidates()
            if (candidates.size <= max || settings.get("rankCands") < 0.5) return candidates.subList(0, Math.min(candidates.size, max))
            val quick: MutableMap<GameAction, Double> = java.util.IdentityHashMap()
            val seed = sandbox.deal()
            // At most a fifth of the decision's time: the moves not screened by then keep their place after the screened ones
            val until = System.nanoTime() + settings.budgetMs() * 200_000L
            for (a in candidates.subList(1, candidates.size)) {
                if (System.nanoTime() > until) break
                val copy = sandbox.fork(room, me, Random(seed))
                try {
                    val sides: Map<String?, BotPlayer> = java.util.Map.of<String?, BotPlayer>(me, HeuristicBot(settings), copy.getOpponentId(me), HeuristicBot(settings))
                    var v = 0.0
                    val soft = SequenceSearch.soft(a)
                    for (m in SequenceSearch.moves(a)) {
                        if (soft && (copy.status != GameRoom.RoomStatus.IN_PROGRESS || me!! != copy.currentTurnUserId)) break
                        val u = sandbox.act(copy, me, m)
                        if (u != null && u.error != null) {
                            if (soft) continue
                            v = Double.NEGATIVE_INFINITY
                            break
                        }
                        SequenceSearch.settle(sandbox, copy, me, sides)
                    }
                    quick[a] = if (v == Double.NEGATIVE_INFINITY) v else TableScore.score(copy, me, settings)
                } finally {
                    sandbox.drop(copy)
                }
            }
            val rest: MutableList<GameAction> = ArrayList(candidates.subList(1, candidates.size))
            // Stable: the screened ones by their table, the rest (unscreened, -1e9) after them in the list's order
            rest.sortWith(Comparator.comparingDouble { a: GameAction -> -quick.getOrDefault(a, -1e9) })
            val out: MutableList<GameAction> = ArrayList(java.util.List.of(candidates[0]))
            out.addAll(rest.subList(0, max - 1))
            return out
        }

        /** The move with the best mean score among those tried (the earliest on a tie). */
        private fun bestMean(sum: DoubleArray, tries: IntArray): Int {
            var best = 0
            for (i in 1 until sum.size) if (tries[i] > 0 && sum[i] / tries[i] > sum[best] / tries[best]) best = i
            return best
        }
    }
}
