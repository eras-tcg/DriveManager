package com.drive.drive_manager.simulator.bot

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

    private val SANDBOX: ThreadLocal<Sandbox> = ThreadLocal.withInitial { Sandbox() }
    /**
     * Verdicts already found, per thread: the same card in the same room with the same hand, field, Tributos and gems
     * gets the same answer (a simulation asks again at every step of a turn). Emptied when it grows large.
     */
    private val SEEN: ThreadLocal<MutableMap<String, Boolean>> = ThreadLocal.withInitial { java.util.HashMap() }

    /** How often it was asked and how often the answer was "nothing" (the bot runs report them). */
    @JvmField
    val ASKED = java.util.concurrent.atomic.AtomicLong()
    @JvmField
    val NOTHING = java.util.concurrent.atomic.AtomicLong()
    /** Debug: the first tables that differed, before and after (null: not kept). */
    @JvmField
    @Volatile
    var diffs: MutableList<String>? = null
    /** Debug: per card asked about as a yes, how often it did something and how often nothing. */
    @JvmField
    val BY_CARD: MutableMap<String, IntArray> = java.util.concurrent.ConcurrentHashMap()

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
        return paysOff(room, me, play, null, java.util.Set.of(id), rules)
    }

    /** The same for a yes to an optional effect of a utility of its own: against the no. */
    @JvmStatic
    fun yesPaysOff(room: GameRoom, me: String?, prompt: Prompt, yes: List<String?>, rules: BotSettings): Boolean {
        val self = room.getPlayer(me)
        if (self == null || yes.isEmpty()) return true
        val moving: MutableSet<String?> = java.util.HashSet(yes)
        val source = if (prompt.sourceName == null) "" else BotSettings.cardKey(prompt.sourceName)
        var utility = false
        for (zone in java.util.List.of(self.hand, self.tributeZone, self.field, self.lifeStack)) {
            for (c in zone) {
                if (BotSettings.cardKey(c.cardName) == source) moving.add(c.instanceId)
                if (moving.contains(c.instanceId) && "utility" == c.cardType) utility = true
            }
        }
        for (c in prompt.cards) if (yes.contains(c.instanceId) && "utility" == c.cardType) utility = true
        if (!utility) return true
        val accept = BotPlayer.action(ActionType.RESOLVE_PROMPT, java.util.Map.of<String, Any?>("promptId", prompt.id, "chosen", yes, "order", java.util.List.of<Any>()))
        val decline = BotPlayer.action(ActionType.RESOLVE_PROMPT, java.util.Map.of<String, Any?>("promptId", prompt.id, "chosen", java.util.List.of<Any>(), "order", java.util.List.of<Any>()))
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
            val sides = java.util.Map.of<String?, BotPlayer>(me, HeuristicBot(plain), copy.getOpponentId(me), HeuristicBot(plain))
            // Without the move, the card is not there to play later in the turn either
            if (without) for (p in copy.players.values) p.hand.removeIf { c -> moving.contains(c.instanceId) }
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
        if (!ActionType.PLAY_CARD.`is`(play)) return true
        val id = play.getString("instanceId")
        val card = if (room.getPlayer(me) == null) null else room.getPlayer(me)!!.hand.firstOrNull { c -> c.instanceId!! == id }
        if (card == null || "utility" != card.cardType) return true
        val r = doesSomething(room, me, play, null, java.util.Set.of(id), rules)
        val n = BY_CARD.computeIfAbsent("[jugar] " + card.cardName) { IntArray(2) }
        synchronized(n) { n[if (r) 0 else 1]++ }
        return r
    }

    /** A yes to an optional effect of its own ("puedes jugar esta carta sin pagar su coste"): the card it plays may move. */
    @JvmStatic
    fun yesDoesSomething(room: GameRoom, me: String?, prompt: Prompt, yes: List<String?>, rules: BotSettings): Boolean {
        val source = if (prompt.sourceName == null) "" else BotSettings.cardKey(prompt.sourceName)
        val self = room.getPlayer(me) ?: return true
        // The card the effect is of (asked as a yes: its name; as the effects to chain: the card chosen) may move
        val names: MutableSet<String> = java.util.HashSet(java.util.Set.of(source))
        for (c in prompt.cards) if (yes.contains(c.instanceId)) names.add(BotSettings.cardKey(c.cardName))
        val moving: MutableSet<String?> = java.util.HashSet(yes)
        for (zone in java.util.List.of(self.hand, self.tributeZone, self.field)) {
            for (c in zone) if (names.contains(BotSettings.cardKey(c.cardName))) moving.add(c.instanceId)
        }
        // Only utilities are judged: a structure or a creature stays and is worth having (its value is the table's)
        var utility = false
        for (zone in java.util.List.of(self.hand, self.tributeZone, self.field, self.lifeStack)) {
            for (c in zone) if (moving.contains(c.instanceId) && "utility" == c.cardType) utility = true
        }
        for (c in prompt.cards) if (yes.contains(c.instanceId) && "utility" == c.cardType) utility = true
        if (!utility) return true
        val accept = BotPlayer.action(ActionType.RESOLVE_PROMPT, java.util.Map.of<String, Any?>("promptId", prompt.id, "chosen", yes, "order", java.util.List.of<Any>()))
        val decline = BotPlayer.action(ActionType.RESOLVE_PROMPT, java.util.Map.of<String, Any?>("promptId", prompt.id, "chosen", java.util.List.of<Any>(), "order", java.util.List.of<Any>()))
        val r = doesSomething(room, me, accept, decline, moving, rules)
        val n = BY_CARD.computeIfAbsent(names.toString() + " " + prompt.purpose + " " + prompt.kind) { IntArray(2) }
        synchronized(n) { n[if (r) 0 else 1]++ }
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
        for (zone in java.util.List.of(p!!.hand, p.field, p.tributeZone)) {
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
        val something = yes != no
        ASKED.incrementAndGet()
        if (!something) NOTHING.incrementAndGet()
        else if (diffs != null && diffs!!.size < 8 && no != null && ActionType.RESOLVE_PROMPT.`is`(move)) diffs!!.add(move.type + " " + firstDiff(no, yes))
        return something
    }

    /** The table once the move is played and what it opens answered (null: refused, or the game ended). */
    private fun outcome(room: GameRoom, me: String?, move: GameAction?, moving: Set<String?>, rules: BotSettings): String? {
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
                SequenceSearch.settle(sandbox, copy, me, java.util.Map.of<String?, BotPlayer>(me, HeuristicBot(plain), copy.getOpponentId(me), HeuristicBot(plain)))
            }
            if (copy.status != GameRoom.RoomStatus.IN_PROGRESS) return null
            // The gems with what was paid to play cards given back: paying is not doing
            val paid = copy.timeline.subList(events, copy.timeline.size)
                    .filter { e -> GameEvent.PLAY == e.type && e.data != null && e.data!!["cost"] is Number }
                    .sumOf { e -> (e.data!!["cost"] as Number).toInt() }
            return table(copy, moving) + "|gems " + (EffectEngine.gemsOf(copy, me) + paid)
        } finally {
            sandbox.drop(copy)
        }
    }

    private fun firstDiff(a: String, b: String): String {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return "@" + i + " [" + a.substring(Math.max(0, i - 60), Math.min(a.length, i + 120)) + "] vs [" + b.substring(Math.max(0, i - 60), Math.min(b.length, i + 120)) + "]"
    }

    /** Every card of both sides but the ones that may move, zone by zone, and the grants on the field. */
    private fun table(room: GameRoom, moving: Set<String?>): String {
        val sb = StringBuilder()
        for (p in room.players.values) {
            sb.append(p.userId).append('|')
            // Every card as it stands (keywords and marks given included), but not where it is drawn on the table nor
            // the order of a zone, but the deck's (what comes next)
            for (zone in java.util.List.of(p.hand, p.field, p.tributeZone, p.discardPile, p.deck, p.lifeStack)) {
                val cards = zone.filter { c -> !moving.contains(c.instanceId) }.map { c -> card(c) }
                sb.append(java.lang.String.join(",", if (zone === p.deck) cards else cards.sorted())).append('/')
            }
            // A card that may move but stays on the field (a utility kept in the Retaguardia) is worth something there
            for (c in p.field) if (moving.contains(c.instanceId)) sb.append("kept ").append(c.instanceId)
        }
        return sb.append(room.fieldGrants as Any?).toString()
    }

    private val PLACE: java.util.regex.Pattern = java.util.regex.Pattern.compile("\\b[xy]=[^,)]*, ?")

    private fun card(c: CardSlot): String {
        return PLACE.matcher(c.toString()).replaceAll("") + ":" + EffectEngine.strength(c)
    }
}
