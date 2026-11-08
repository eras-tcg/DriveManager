package com.drive.drive_manager.simulator.bot

import com.drive.drive_manager.simulator.platform.Clocks
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmRecord
import kotlin.math.max
import kotlin.math.min
import com.drive.drive_manager.simulator.engine.valueOr
import com.drive.drive_manager.simulator.engine.comparingDouble
import com.drive.drive_manager.simulator.engine.frozenListOf
import com.drive.drive_manager.simulator.engine.frozenMapOf
import com.drive.drive_manager.simulator.engine.EffectEngine
import com.drive.drive_manager.simulator.engine.LegalActions
import com.drive.drive_manager.simulator.engine.Prompt
import com.drive.drive_manager.simulator.engine.PromptPurpose
import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState

/**
 * The bot that looks one move ahead (phase 2). For each move it could make now (or each answer to its choice) it
 * plays the move on a copy of the room (see [Sandbox]), lets [HeuristicBot]s play both sides until the
 * turn passes, and scores the table it is left with ([score]). It makes the move with the best score;
 * the heuristic's own move is tried first and kept on a tie, and when the time runs out.
 *
 * How far it looks, how many times it tries each move and what a table is worth are its [BotSettings], so
 * variants can be played against each other (the bot arena) to tune them. With a [ValueModel] the table is
 * scored by the model (the chance of winning from it, learned from games) instead of the hand-set weights.
 */
open class LookaheadBot(settings: BotSettings, model: ValueModel?) : HeuristicBot(settings) {

    /** What its decisions cost: how many it looked ahead for, their time, and how many ran out of time. */
    @JvmRecord
    data class Stats(val decisions: Int, val totalNanos: Long, val maxNanos: Long, val outOfTime: Int, val movesTried: Long)

    /**
     * One decision it looked ahead for, as it saw it (for audits): whose turn it was, the move chosen and its card (or
     * the card asking, for an answer) and target, among how many moves and which of them (0: the heuristic's), the score
     * it expected the table to have when the look-ahead ends, and the table's score now.
     */
    @JvmRecord
    data class Decision(val me: String?, val turnOf: String?, val type: String?, val card: String?, val target: String?, val candidates: Int, val index: Int,
                        val expected: Double, val now: Double)

    private val settings: BotSettings = settings
    /** The trained model that scores a table; null scores it with the weights of the settings. */
    private val model: ValueModel? = model
    private val sandbox = Sandbox()
    /** The last decision, so asking again at the same moment (the room service asks before and when moving) costs nothing. */
    private var decidedAt: String? = null
    private var decided: Any? = null

    /** Told of every decision the bot looks ahead for (a `Consumer<Decision>` as before). */
    fun interface Watcher {
        fun accept(decision: Decision)
    }

    /** Told of every decision it looks ahead for; none in the server. */
    private var watcher: Watcher? = null

    private var specialChances = 0
    private var specials = 0
    private var tributeChances = 0
    private var tributedCreatures = 0
    private var tributedOthers = 0
    private var decisions = 0
    private var totalNanos: Long = 0
    private var maxNanos: Long = 0
    private var outOfTime = 0
    /** planKeep: the rest of the plan it chose (PlanKeep). */
    private val plan = PlanKeep()
    private var movesTried: Long = 0

    constructor() : this(BotSettings.DEFAULT)

    constructor(settings: BotSettings) : this(settings, null)

    fun model(): ValueModel? {
        return model
    }

    fun settings(): BotSettings {
        return settings
    }

    override fun seed(seed: Long) {
        sandbox.seed(seed)
    }

    fun stats(): Stats {
        return Stats(decisions, totalNanos, maxNanos, outOfTime, movesTried)
    }

    /**
     * How it used its chances (for the tests): main decisions with a special summon among the moves, and how many it
     * made; cards it sent from the hand to the Tributos when it could choose between creatures and other cards.
     */
    @JvmRecord
    data class Choices(val specialChances: Int, val specials: Int, val tributeChances: Int, val tributedCreatures: Int, val tributedOthers: Int)

    fun choices(): Choices {
        return Choices(specialChances, specials, tributeChances, tributedCreatures, tributedOthers)
    }

    fun watch(watcher: Watcher?) {
        this.watcher = watcher
    }

    // ------------------------------------------------------------------ Decisions

    override fun mainMove(room: GameRoom, me: String?): GameAction? {
        val at = moment(room, "main")
        if (at == decidedAt) return decided as GameAction?
        val heuristic = super.mainMove(room, me)
        val candidates: MutableList<GameAction> = ArrayList()
        candidates.add(heuristic ?: endTurn())
        for (move in mainMoves(room, me)) if (!isRefused(move) && !same(move, candidates[0]) && (!on("hUseful") || Usefulness.playDoesSomething(room, me, move, settings))) candidates.add(move)
        if (heuristic != null) candidates.add(endTurn())
        if (on("hUsefulEnd")) candidates.removeAll { m -> !Usefulness.playPaysOff(room, me, m, settings) } // a utility that leaves the turn no better
        candidates.addAll(1, SequenceSearch.best(sandbox, room, me, candidates, settings)); plan.offer(room, me, candidates, settings)
        val best = remember(at, plan.next(sandbox, room, me, best(room, me, candidates), settings)) as GameAction?
        if (candidates.any { a -> ActionType.RITUAL_SUMMON.`is`(a) }) {
            specialChances++
            if (best != null && ActionType.RITUAL_SUMMON.`is`(best)) specials++
        }
        return if (best == null || ActionType.END_TURN.`is`(best)) null else best
    }

    override fun answer(room: GameRoom, me: String?, prompt: Prompt): List<String>? {
        val at = moment(room, "prompt " + prompt.id)
        @Suppress("UNCHECKED_CAST")
        val cached = if (at == decidedAt) decided as List<String>? else null
        if (cached != null) return cached
        val heuristic: List<String>? = super.answer(room, me, prompt)
        // hUseful: the heuristic said no to an optional effect of its own because it would do nothing
        if (on("hUseful") && optionalEffect(prompt) && heuristic != null && heuristic.isEmpty()) return heuristic
        if (on("hUsefulEnd") && optionalEffect(prompt) && prompt.min == 0 && heuristic != null && !heuristic.isEmpty() && !Usefulness.yesPaysOff(room, me, prompt, heuristic, settings)) return frozenListOf()
        val options: MutableSet<List<String?>> = LinkedHashSet(); if (heuristic != null) options.add(heuristic)
        val choices: List<String?> = choices(prompt)
        val self = room.getPlayer(me)
        if (on("hTributeCreatures") && tributesFromHand(self, prompt, choices)) {
            // Which cards go to the Tributos is the rule's (the table's score cannot tell a creature there from a utility
            // kept in the hand); how many, when the card leaves it open, is tried: the first k in the rule's order
            val order = choices.sortedWith(comparingDouble { id: String? -> tributeOrder(cardIn(prompt, id)) })
            for (k in prompt.min..min(prompt.max, order.size)) options.add(ArrayList(order.subList(0, k)))
        } else {
            // A special summon it began is not called off unless the heuristic finds nothing to choose: in the copies the
            // heuristic summons it again for the rest of the turn, so calling it off scores as well and wins by noise
            val begun = PromptPurpose.SPECIAL.`is`(prompt.purpose) || PromptPurpose.RITUAL.`is`(prompt.purpose)
            if (prompt.min == 0 && !(begun && heuristic != null && !heuristic.isEmpty())) options.add(frozenListOf())
            if (prompt.min <= 1 && prompt.max >= 1) choices.forEach { id -> options.add(frozenListOf(id)) }
            if (prompt.max > 1 && choices.size >= max(prompt.min, 2)) {
                options.add(ArrayList(choices.subList(0, min(prompt.max, choices.size)))); AdvanceOptions.add(options, room, self, prompt, choices, settings)
            }
            // With a loss for utilities in the Tributos (or the hand's ranking, hRank), the table can tell them apart: it also tries the weakest first
            if ((settings.uTribute != 0.0 || HandRank.on(settings)) && tributesFromHand(self, prompt, choices)) {
                val order: List<String?> = if (HandRank.on(settings)) HandRank.weakestFirst(room, self!!, choices, settings) else choices.sortedWith(comparingDouble { id: String? -> tributeOrder(cardIn(prompt, id)) })
                for (k in max(1, prompt.min)..min(prompt.max, order.size)) options.add(ArrayList(order.subList(0, k)))
            }
        }
        if (options.size <= 1) return counted(self, prompt, choices, heuristic)
        val candidates: MutableList<GameAction> = ArrayList()
        for (chosen in options) {
            val a = action(ActionType.RESOLVE_PROMPT, frozenMapOf<String, Any?>("promptId", prompt.id, "chosen", chosen, "order", frozenListOf<Any>()))
            if (!isRefused(a)) candidates.add(a)
        }
        val best = best(room, me, candidates)
        val chosen: List<String>? = if (best == null) heuristic else best.getStringList("chosen")
        remember(at, chosen)
        return counted(self, prompt, choices, chosen)
    }

    /** Counts what it sends from the hand to the Tributos when it could choose between creatures and other cards. */
    private fun counted(self: PlayerState?, prompt: Prompt, choices: List<String?>, chosen: List<String>?): List<String>? {
        if (chosen == null || !tributesFromHand(self, prompt, choices)) return chosen
        val offered = choices.map { id -> cardIn(prompt, id) }.filter { c -> c != null }.map { c -> c!! }
        val mixed = offered.any { c -> "creature" == c.cardType } && offered.any { c -> "creature" != c.cardType }
        if (!mixed) return chosen
        tributeChances++
        for (id in chosen) {
            val c = cardIn(prompt, id)
            if (c != null && "creature" == c.cardType) tributedCreatures++
            else tributedOthers++
        }
        return chosen
    }

    // ------------------------------------------------------------------ Trying moves

    /** The candidate with the best score (the first one, the heuristic's, on a tie or when time runs out): [MoveTrials]. */
    private fun best(room: GameRoom, me: String?, candidates: List<GameAction>): GameAction? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]
        val start = Clocks.nanos()
        val trials = MoveTrials(settings, sandbox, TableScoring { r: GameRoom, p: String? -> score(r, p) }, start + settings.budgetMs() * 1_000_000L)
        val best = trials.best(room, me, MoveTrials.top(sandbox, room, me, candidates, settings))
        val spent = Clocks.nanos() - start
        decisions++
        totalNanos += spent
        maxNanos = max(maxNanos, spent)
        if (trials.ranOutOfTime) outOfTime++
        movesTried += trials.movesTried.toLong()
        val w = watcher
        if (w != null) w.accept(decision(room, me, best, candidates, trials.expected))
        return best
    }

    private fun decision(room: GameRoom, me: String?, chosen: GameAction?, candidates: List<GameAction>, expected: Double): Decision {
        val answer = ActionType.RESOLVE_PROMPT.`is`(chosen)
        val card = if (answer) (if (room.prompt == null) null else room.prompt!!.sourceName) else nameOf(room, chosen!!.getString("instanceId"))
        return Decision(me, room.currentTurnUserId, chosen!!.type, card, nameOf(room, chosen.getString("targetInstanceId")),
                candidates.size, candidates.indexOf(chosen), expected, score(room, me))
    }

    // ------------------------------------------------------------------ Scoring a table

    /** The table's score for the bot: a game won or lost, else the model's chance of winning (as 0–100), else the weights. */
    private fun score(room: GameRoom, me: String?): Double {
        if (model == null || room.status != GameRoom.RoomStatus.IN_PROGRESS) return TableScore.score(room, me, settings)
        return model.winChance(room, me) * 100
    }

    // ------------------------------------------------------------------ Helpers

    /** The moment of a decision: the timeline, the ids handed out, what was refused and what is decided. */
    private fun moment(room: GameRoom, what: String): String {
        return room.eventSeq.toString() + "|" + room.idSeq + "|" + refusals() + "|" + what
    }

    private fun remember(at: String, decision: Any?): Any? {
        decidedAt = at
        decided = decision
        return decision
    }

    companion object {

        private fun cardIn(prompt: Prompt, id: String?): CardSlot? {
            return prompt.cards.firstOrNull { c -> id!! == c.instanceId }
        }

        /** Every move the rules allow now: each attack (to the rival and to each enemy it may attack), summon, play, Ritual and slow effect. */
        @JvmStatic
        fun mainMoves(room: GameRoom, me: String?): List<GameAction> {
            val moves: MutableList<GameAction> = ArrayList()
            val legal = LegalActions.of(room, me)
            val targets = LegalActions.attackTargets(room, me)
            val x = HeuristicBot.summonX(room.getPlayer(me))
            for ((id, can) in legal) {
                if (can.contains("attack")) {
                    moves.add(BotPlayer.action(ActionType.ATTACK, frozenMapOf<String, Any?>("instanceId", id)))
                    for (target in targets.valueOr(id, frozenListOf())) moves.add(BotPlayer.action(ActionType.ATTACK, frozenMapOf<String, Any?>("instanceId", id, "targetInstanceId", target)))
                }
                if (can.contains("summon")) moves.add(BotPlayer.action(ActionType.NORMAL_SUMMON, frozenMapOf<String, Any?>("instanceId", id, "x", x, "y", 75.0)))
                if (can.contains("play")) moves.add(BotPlayer.action(ActionType.PLAY_CARD, frozenMapOf<String, Any?>("instanceId", id, "x", x, "y", 75.0)))
                if (can.contains("ritual")) moves.add(BotPlayer.action(ActionType.RITUAL_SUMMON, frozenMapOf<String, Any?>("instanceId", id)))
                if (can.contains("activate")) moves.add(BotPlayer.action(ActionType.ACTIVATE_EFFECT, frozenMapOf<String, Any?>("instanceId", id)))
            }
            return moves
        }

        private fun nameOf(room: GameRoom, instanceId: String?): String? {
            val c = if (instanceId == null) null else EffectEngine.findInRoom(room, instanceId)
            return c?.cardName
        }

        private fun endTurn(): GameAction {
            return BotPlayer.action(ActionType.END_TURN, frozenMapOf())
        }

        private fun same(a: GameAction, b: GameAction): Boolean {
            return a.type!! == b.type && a.payload!! == b.payload
        }
    }
}
