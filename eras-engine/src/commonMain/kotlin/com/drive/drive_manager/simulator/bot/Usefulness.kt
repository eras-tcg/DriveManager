package com.drive.drive_manager.simulator.bot

import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmField
import com.drive.drive_manager.simulator.platform.withLock
import com.drive.drive_manager.simulator.platform.computeIfAbsentAtomic
import com.drive.drive_manager.simulator.platform.Platform
import com.drive.drive_manager.simulator.platform.LocalValue
import com.drive.drive_manager.simulator.platform.AtomicCounter
import kotlin.math.max
import kotlin.math.min
import com.drive.drive_manager.simulator.engine.frozenListOf
import com.drive.drive_manager.simulator.engine.frozenSetOf
import com.drive.drive_manager.simulator.engine.frozenMapOf
import com.drive.drive_manager.simulator.engine.EffectEngine
import com.drive.drive_manager.simulator.engine.GameEvent
import com.drive.drive_manager.simulator.engine.Prompt
import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.engine.GameRandom

/**
 * Does a move do anything? It is played on a copy of the room, what it opens answered by the heuristic until the
 * table is quiet, and the table compared with the one before: every card of both sides (where it is and how it
 * stands), the grants on the field and the bot's gems once the price is paid. Only the card played may move (from the
 * hand or the Tributos to the Descarte). A utility whose effect finds nothing to do (no creature to summon, no target
 * worth it) changes nothing else: the bot keeps it for when it does (hUseful).
 */
internal object Usefulness {

    private val SANDBOX: LocalValue<Sandbox> = LocalValue { Sandbox() }
    /**
     * Verdicts already found, per thread: the same card in the same room with the same hand, field, Tributos and gems
     * gets the same answer (a simulation asks again at every step of a turn). Emptied when it grows large.
     */
    private val SEEN: LocalValue<MutableMap<String, Boolean>> = LocalValue { HashMap() }

    /**
     * Runs `block` with `memory` as this thread's verdicts, then puts back the thread's own: a game that must play the
     * same from its seed whatever ran before on the thread (`com.eras.engine.local.LocalMatch`) keeps a memory of its own.
     */
    internal fun <T> withMemory(memory: MutableMap<String, Boolean>, block: () -> T): T {
        val before = SEEN.get()
        SEEN.set(memory)
        try {
            return block()
        } finally {
            SEEN.set(before)
        }
    }

    /** How often it was asked and how often the answer was "nothing" (the bot runs report them). */
    @JvmField
    val ASKED = AtomicCounter()
    @JvmField
    val NOTHING = AtomicCounter()
    /** Debug: the first tables that differed, before and after (null: not kept). */
    @JvmField
    @kotlin.concurrent.Volatile
    var diffs: MutableList<String>? = null
    /** Debug: per card asked about as a yes, how often it did something and how often nothing. */
    @JvmField
    val BY_CARD: MutableMap<String, IntArray> = Platform.concurrentMap()

    /**
     * hUsefulEnd, for the bot's own decisions: is the turn better with the move than without it? The rest of the turn
     * is played on two copies dealt alike by the heuristic, one with the move and one without the card (out of the
     * hand), and the table's score compared once the turn passes. What lasts only until the end of the turn and is not
     * used (a keyword to a creature that cannot attack) is gone by then, and a price that costs a later summon counts.
     */
    @JvmStatic
    fun playPaysOff(room: GameRoom, me: String?, play: GameAction, rules: BotSettings): Boolean {
        if (!ActionType.PLAY_CARD.`is`(play)) return true
        val id = play.getString("instanceId")
        val card = if (room.getPlayer(me) == null) null else room.getPlayer(me)!!.hand.firstOrNull { c -> c.instanceId!! == id }
        if (card == null || "utility" != card.cardType) return true
        return paysOff(room, me, play, null, frozenSetOf(id), rules)
    }

    /** The same for a yes to an optional effect of a utility of its own: against the no. */
    @JvmStatic
    fun yesPaysOff(room: GameRoom, me: String?, prompt: Prompt, yes: List<String?>, rules: BotSettings): Boolean {
        val self = room.getPlayer(me)
        if (self == null || yes.isEmpty()) return true
        val moving: MutableSet<String?> = HashSet(yes)
        val source = if (prompt.sourceName == null) "" else BotSettings.cardKey(prompt.sourceName)
        var utility = false
        for (zone in frozenListOf(self.hand, self.tributeZone, self.field, self.lifeStack)) {
            for (c in zone) {
                if (BotSettings.cardKey(c.cardName) == source) moving.add(c.instanceId)
                if (moving.contains(c.instanceId) && "utility" == c.cardType) utility = true
            }
        }
        for (c in prompt.cards) if (yes.contains(c.instanceId) && "utility" == c.cardType) utility = true
        if (!utility) return true
        val accept = BotPlayer.action(ActionType.RESOLVE_PROMPT, frozenMapOf<String, Any?>("promptId", prompt.id, "chosen", yes, "order", frozenListOf<Any>()))
        val decline = BotPlayer.action(ActionType.RESOLVE_PROMPT, frozenMapOf<String, Any?>("promptId", prompt.id, "chosen", frozenListOf<Any>(), "order", frozenListOf<Any>()))
        return paysOff(room, me, accept, decline, moving, rules)
    }

    private fun paysOff(room: GameRoom, me: String?, move: GameAction, without: GameAction?, moving: Set<String?>, rules: BotSettings): Boolean {
        val seen = SEEN.get()
        val key = "end|" + key(room, me, move)
        val known = seen[key]
        if (known != null) return known
        if (seen.size > 4096) seen.clear()
        val yes = endScore(room, me, move, moving, rules, false)
        val no = endScore(room, me, without, moving, rules, true)
        val better = yes == null || no == null || yes > no + 1e-6
        ASKED.incrementAndGet()
        if (!better) NOTHING.incrementAndGet()
        seen[key] = better
        return better
    }

    /** The score once the turn passes, the move played (or none) and the rest of the turn by the heuristic; null: refused. */
    private fun endScore(room: GameRoom, me: String?, move: GameAction?, moving: Set<String?>, rules: BotSettings, without: Boolean): Double? {
        val sandbox = SANDBOX.get()
        val copy = sandbox.fork(room, me, GameRandom(1))
        try {
            val plain = rules.with("hUseful=0,hUsefulEnd=0")
            val sides = frozenMapOf<String?, BotPlayer>(me, HeuristicBot(plain), copy.getOpponentId(me), HeuristicBot(plain))
            // Without the move, the card is not there to play later in the turn either
            if (without) for (p in copy.players.values) p.hand.removeAll { c -> moving.contains(c.instanceId) }
            val turn = copy.currentTurnUserId
            if (move != null) {
                val u = sandbox.act(copy, me, move)
                if (u != null && u.error != null) return null
            }
            var step = 0
            while (step < MoveTrials.MAX_STEPS && copy.status == GameRoom.RoomStatus.IN_PROGRESS && turn != null && turn == copy.currentTurnUserId) {
                val seat = if (copy.prompt != null) copy.prompt!!.playerId else copy.currentTurnUserId
                val side = sides[seat]
                val next = (if (side == null) null else side.next(copy, seat)) ?: break
                val u = sandbox.act(copy, seat, next)
                if (u != null && u.error != null) side!!.refused(next, u.error)
                else side!!.played(copy, next)
                step++
            }
            return TableScore.score(copy, me, rules)
        } finally {
            sandbox.drop(copy)
        }
    }

    /** A play from the hand: PLAY_CARD of a utility. Other moves always do something. */
    @JvmStatic
    fun playDoesSomething(room: GameRoom, me: String?, play: GameAction, rules: BotSettings): Boolean {
        // A slow effect activated: judged the same, against not activating it. The card stays where it is, so how it
        // stands itself (exhausted to pay) does not count as a change; what it gives up counts as material
        if (ActionType.ACTIVATE_EFFECT.`is`(play)) return doesSomething(room, me, play, null, frozenSetOf(play.getString("instanceId")), rules)
        if (!ActionType.PLAY_CARD.`is`(play)) return true
        val id = play.getString("instanceId")
        val card = if (room.getPlayer(me) == null) null else room.getPlayer(me)!!.hand.firstOrNull { c -> c.instanceId!! == id }
        if (card == null || "utility" != card.cardType) return true
        val r = doesSomething(room, me, play, null, frozenSetOf(id), rules)
        val n = BY_CARD.computeIfAbsentAtomic("[jugar] " + card.cardName) { IntArray(2) }
        withLock(n) { n[if (r) 0 else 1]++ }
        return r
    }

    /** A yes to an optional effect of its own ("puedes jugar esta carta sin pagar su coste"): the card it plays may move. */
    @JvmStatic
    fun yesDoesSomething(room: GameRoom, me: String?, prompt: Prompt, yes: List<String?>, rules: BotSettings): Boolean {
        val source = if (prompt.sourceName == null) "" else BotSettings.cardKey(prompt.sourceName)
        val self = room.getPlayer(me) ?: return true
        // The card the effect is of (asked as a yes: its name; as the effects to chain: the card chosen) may move
        val names: MutableSet<String> = HashSet(frozenSetOf(source))
        for (c in prompt.cards) if (yes.contains(c.instanceId)) names.add(BotSettings.cardKey(c.cardName))
        val moving: MutableSet<String?> = HashSet(yes)
        for (zone in frozenListOf(self.hand, self.tributeZone, self.field)) {
            for (c in zone) if (names.contains(BotSettings.cardKey(c.cardName))) moving.add(c.instanceId)
        }
        // Only utilities are judged: a structure or a creature stays and is worth having (its value is the table's)
        var utility = false
        for (zone in frozenListOf(self.hand, self.tributeZone, self.field, self.lifeStack)) {
            for (c in zone) if (moving.contains(c.instanceId) && "utility" == c.cardType) utility = true
        }
        for (c in prompt.cards) if (yes.contains(c.instanceId) && "utility" == c.cardType) utility = true
        if (!utility) return true
        val accept = BotPlayer.action(ActionType.RESOLVE_PROMPT, frozenMapOf<String, Any?>("promptId", prompt.id, "chosen", yes, "order", frozenListOf<Any>()))
        val decline = BotPlayer.action(ActionType.RESOLVE_PROMPT, frozenMapOf<String, Any?>("promptId", prompt.id, "chosen", frozenListOf<Any>(), "order", frozenListOf<Any>()))
        val r = doesSomething(room, me, accept, decline, moving, rules)
        val n = BY_CARD.computeIfAbsentAtomic(names.toString() + " " + prompt.purpose + " " + prompt.kind) { IntArray(2) }
        withLock(n) { n[if (r) 0 else 1]++ }
        return r
    }

    /**
     * The table after the move against the table without it: with no move for a play from the hand (the turn is
     * quiet), else after the "no" (both copies dealt alike and played on alike, so only the effect differs).
     */
    private fun doesSomething(room: GameRoom, me: String?, move: GameAction, without: GameAction?, moving: Set<String?>, rules: BotSettings): Boolean {
        val seen = SEEN.get()
        val key = key(room, me, move)
        val known = seen[key]
        if (known != null) return known
        if (seen.size > 4096) seen.clear()
        val found = judge(room, me, move, without, moving, rules)
        seen[key] = found
        return found
    }

    /** The room (this copy), the move and what the bot holds: hand, field (and how it stands), Tributos, gems, phase. */
    private fun key(room: GameRoom, me: String?, move: GameAction): String {
        val p = room.getPlayer(me)
        val sb = StringBuilder(room.roomId!!).append('|').append(move.type).append(move.payload as Any?).append('|')
                .append(EffectEngine.gemsOf(room, me)).append(room.currentTurnUserId).append(room.chain.size)
        for (zone in frozenListOf(p!!.hand, p.field, p.tributeZone)) {
            sb.append('/')
            for (c in zone) sb.append(c.instanceId).append(if (c.tapped) "t," else ",")
        }
        val other = room.getPlayer(room.getOpponentId(me))
        if (other != null) for (c in other.field) sb.append(c.instanceId).append(if (c.tapped) "t," else ",")
        return sb.toString()
    }

    private fun judge(room: GameRoom, me: String?, move: GameAction, without: GameAction?, moving: Set<String?>, rules: BotSettings): Boolean {
        val yes = outcome(room, me, move, moving, rules) ?: return true
        val no = if (without == null) outcome(room, me, null, moving, rules) else outcome(room, me, without, moving, rules)
        // An advantage, not a change (the user's call, 2026-11-11): the table must differ from the one without the move,
        // and the bot must not hold less material by it. Paying a cost is a change too; when that is all that happened
        // (a card tributed for an effect that found nothing, a creature sent to attack into a block that destroys it)
        // the move is refused. One that changes the table at no loss (a search, a keyword, a reduction) goes on to the tries
        // Nor may it empty its own deck (the user's game, 2026-11-11: st1-st2 s13095): only the draw at the start of the
        // turn loses, so the last cards are not a cost to pay. The heuristic paid them (Ddraig and Wyfreik tribute three
        // from the top to attack), so in the tries the rival ran out of deck by itself and the bot counted on a win
        // that the real rival never gave it
        val something = no == null || (yes.table != no.table && yes.material >= no.material - 1e-9 && !(yes.deck == 0 && no.deck > 0))
        ASKED.incrementAndGet()
        if (!something) NOTHING.incrementAndGet()
        else if (diffs != null && diffs!!.size < 8 && no != null && ActionType.RESOLVE_PROMPT.`is`(move)) diffs!!.add(move.type + " " + firstDiff(no.table, yes.table))
        return something
    }

    /** The table once the move is played and what it opens answered (null: refused, or the game ended). */
    private fun outcome(room: GameRoom, me: String?, move: GameAction?, moving: Set<String?>, rules: BotSettings): Outcome? {
        val sandbox = SANDBOX.get()
        val copy = sandbox.fork(room, me, GameRandom(1))
        try {
            val events = copy.timeline.size
            if (move != null) {
                val u = sandbox.act(copy, me, move)
                // Refused: the engine says so to the bot itself
                if (u != null && u.error != null) return null
                // What the move opens, answered without this check (no copies within copies)
                val plain = rules.with("hUseful=0")
                SequenceSearch.settle(sandbox, copy, me, frozenMapOf<String?, BotPlayer>(me, HeuristicBot(plain), copy.getOpponentId(me), HeuristicBot(plain)))
            }
            if (copy.status != GameRoom.RoomStatus.IN_PROGRESS) return null
            // The gems with what was paid to play cards given back: paying is not doing
            val paid = copy.timeline.subList(events, copy.timeline.size)
                    .filter { e -> GameEvent.PLAY == e.type && e.data != null && e.data!!["cost"] is Number }
                    .sumOf { e -> (e.data!!["cost"] as Number).toInt() }
            val gems = EffectEngine.gemsOf(copy, me) + paid
            return Outcome(table(copy, moving) + "|gems " + gems, material(copy, me, moving, rules) + gems * rules.hand, copy.getPlayer(me)?.deck?.size ?: 0)
        } finally {
            sandbox.drop(copy)
        }
    }

    /** A table as it stands (to tell whether anything changed) and what it holds for the bot (to tell whether it lost by it). */
    private class Outcome(val table: String, val material: Double, val deck: Int)

    /**
     * The bot's material minus the rival's: each side's lives, cards in the hand (but the ones that may move: spending
     * the card played is not a loss of its effect) and creatures on the field by their worth. A reduced enemy is worth
     * less, a creature of its own lost is its worth lost.
     */
    private fun material(copy: GameRoom, me: String?, moving: Set<String?>, w: BotSettings): Double {
        var v = 0.0
        for (p in copy.players.values) {
            var side = p.lifeStack.size * w.life + p.hand.count { c -> !moving.contains(c.instanceId) } * w.hand
            for (c in p.field) if ("creature" == c.cardType) side += CardWorth.of(c, w)
            v += if (p.userId == me) side else -side
        }
        return v
    }

    private fun firstDiff(a: String, b: String): String {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return "@" + i + " [" + a.substring(max(0, i - 60), min(a.length, i + 120)) + "] vs [" + b.substring(max(0, i - 60), min(b.length, i + 120)) + "]"
    }

    /** Every card of both sides but the ones that may move, zone by zone, and the grants on the field. */
    private fun table(room: GameRoom, moving: Set<String?>): String {
        val sb = StringBuilder()
        for (p in room.players.values) {
            sb.append(p.userId).append('|')
            // Every card as it stands (keywords and marks given included), but not where it is drawn on the table nor
            // the order of a zone, but the deck's (what comes next)
            for (zone in frozenListOf(p.hand, p.field, p.tributeZone, p.discardPile, p.deck, p.lifeStack)) {
                val cards = zone.filter { c -> !moving.contains(c.instanceId) }.map { c -> card(c) }
                sb.append((if (zone === p.deck) cards else cards.sorted()).joinToString(",")).append('/')
            }
            // A card that may move but stays on the field (a utility kept in the Retaguardia) is worth something there
            for (c in p.field) if (moving.contains(c.instanceId)) sb.append("kept ").append(c.instanceId)
        }
        return sb.append(room.fieldGrants as Any?).toString()
    }

    // Where it is drawn and when it entered the field (the order of the events: it differs between two copies whenever
    // one of them recorded more events before, though the table is the same) are not how a card stands
    private val PLACE: Regex = Platform.regex("\\b(?:[xy]|fieldSeq)=[^,)]*, ?")

    private fun card(c: CardSlot): String {
        return PLACE.replace(c.toString(), "") + ":" + EffectEngine.strength(c)
    }
}
