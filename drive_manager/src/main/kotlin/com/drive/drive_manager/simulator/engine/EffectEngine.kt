package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.room.CardLocator
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.FloatingEffect
import com.drive.drive_manager.simulator.room.GameLogEntry
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState
import com.drive.drive_manager.simulator.room.Zone

/**
 * Rules engine of the simulator (Reglamento v1.1). Grows card by card: actions it knows are carried out by
 * the rules; everything else is still played by hand on the board.
 *
 * Flow: an action produces timeline events → once no effect is in the middle of its resolution, those events
 * trigger effects (rule 11.6.0.4) → triggered effects are activated into the chain in rules order (optional
 * ones ask first) → the chain resolves last-in first-out, step by step; a step that needs a choice pauses
 * with a [Prompt] until the player answers. Events of a resolution open a new window when it ends.
 * A combat (rule 10) goes on step by step whenever the chain is empty.
 *
 * By theme: [Summons] (normal and special summons, playing cards), [Removals] (destruction, sacrifice,
 * replacements), [CombatRules], [TurnRules], [Chain] (triggers, chain, priority, slow effects),
 * `Steps` (each effect step), [Answers] (the players' answers), [CostModifiers] and [HistoryDescriber].
 * This class keeps the timeline and the helpers they share.
 */
object EffectEngine {

    const val TIMELINE_LIMIT = 2000
    const val LOG_LIMIT = 300

    /** Why a card was destroyed (rule 12.0.0.1). MANUAL: done from the board, outside the engine's rules. */
    const val CAUSE_EFFECT = "EFFECT"
    const val CAUSE_BATTLE = "BATTLE"
    const val CAUSE_RITUAL = "RITUAL"
    const val CAUSE_MANUAL = "MANUAL"
    /** Destroyed by the game's rules, not by an effect nor a player: strength 0 after the effect's destruction was prevented. */
    const val CAUSE_RULES = "RULES"

    /** Key in the room's per-turn usage: the player already played a structure normally this turn. */
    const val STRUCTURE_PLAYED = "structure-played|"
    /** Turn-scoped marks in usedThisTurn: a Definitive effect used, Última Línea's extra hit, the turn to end. */
    const val DEFINITIVE_USED = "definitive"
    const val EXTRA_HIT = "extra-hit|"
    const val END_TURN_REQUESTED = "end-turn|"
    /** Gained by a creature that cannot declare an attack this turn. */
    const val NO_ATTACK = "No puede atacar"
    /** "no puede declarar bloqueo" / "no puede bloquear". */
    const val NO_BLOCK = "No puede bloquear"
    /** "no puede agotarse": it cannot be exhausted (by effects, nor to declare an attack). */
    const val CANNOT_TAP = "No puede agotarse"
    /** "no puede ser objetivo de efectos de cartas oponentes [nivel N o menor]": "Inmune" or "Inmune N". */
    const val IMMUNE = "Inmune"
    /** "no puede ser removido por efectos oponentes". */
    const val UNREMOVABLE = "Irremovible por efectos oponentes"
    /** "esta carta solo puede ser afectada por copias de sí misma": other cards' effects (yours too) do nothing to it. */
    const val ONLY_COPIES = "Solo la afectan sus copias"

    // An effect of another card that does nothing to this one (ONLY_COPIES).
    /**
     * The card whose rules read the table now: the effect resolving or being checked, or the creature being special summoned. Some cards
     * count as another level for some cards (Abraza Almas: "puede ser considerada nivel 2 por tus cartas [espectro]").
     */
    private val LOOKER = ThreadLocal<CardSlot?>()

    @JvmStatic
    fun <T> lookingAs(looker: CardSlot?, body: java.util.function.Supplier<T>): T {
        val previous = LOOKER.get()
        LOOKER.set(looker)
        try {
            return body.get()
        } finally {
            if (previous == null) LOOKER.remove()
            else LOOKER.set(previous)
        }
    }

    /** The levels the card can count as for the card looking now: its own, and another one some cards may count as. */
    @JvmStatic
    fun levels(card: CardSlot?): List<Int> {
        if (card!!.getLevel() == null) return java.util.List.of()
        val other = CardEffects.alternativeLevel(card, LOOKER.get())
        return if (other == null || other == card.getLevel()) java.util.List.of(card.getLevel()!!) else java.util.List.of(card.getLevel()!!, other)
    }

    /** The card looking now is being special summoned (not an effect reading the table). */
    private val SUMMONING = ThreadLocal<Boolean?>()

    @JvmStatic
    fun <T> summoningAs(summoned: CardSlot?, body: java.util.function.Supplier<T>): T {
        val previous = SUMMONING.get()
        SUMMONING.set(true)
        try {
            return lookingAs(summoned, body)
        } finally {
            if (previous == null) SUMMONING.remove()
            else SUMMONING.set(previous)
        }
    }

    /** The strength a card counts as a creature for the effect looking now (Fósil), or null when it does not. */
    @JvmStatic
    fun creatureForEffects(card: CardSlot?): Int? {
        if ("creature" == card!!.cardType || LOOKER.get() == null || SUMMONING.get() == true) return null
        return CardEffects.creatureStrengthForEffects(card)
    }

    @JvmStatic
    fun hasLevel(card: CardSlot?, level: Int): Boolean {
        return levels(card).contains(level)
    }

    @JvmStatic
    fun unaffected(card: CardSlot?, effect: PendingEffect?): Boolean {
        return effect != null && card != null && keywords(card).contains(ONLY_COPIES) && card.cardName != null
                && !card.cardName!!.equals(effect.sourceName, ignoreCase = true)
    }
    /** Marks in usedThisTurn: a Definitive effect used the turn before ("definitive-last|player"). */
    const val DEFINITIVE_LAST_TURN = "definitive-last|"
    /** An ally of that player was destroyed by the opponent this turn, and in the turn before ("…|last"). */
    const val ALLY_DESTROYED_BY_OPPONENT = "ally-destroyed-by-opponent|"
    const val ALLY_DESTROYED_LAST_TURN = "ally-destroyed-last|"
    /** A face-up life with less strength until the end of the turn: "life-reduced|instance|amount". */
    const val LIFE_REDUCED = "life-reduced|"
    const val SUMMON_SACRIFICE = "summon-sacrifice|"
    /** The mark of an effect's direct damage that waits for that answer: effect id | damage | controller. */
    const val PENDING_EFFECT_DAMAGE = "pending-effect-damage|"
    /** An "until the end of the attack" entry that is strength, not an ability ("id|fuerza:3"). */
    const val STRENGTH_ENTRY = "fuerza:"

    // ------------------------------------------------------------------ Timeline

    /**
     * Adds an event to the timeline (and to the events waiting to trigger effects) and, when `logText`
     * is given, a line to the text log.
     */
    @JvmStatic
    fun record(
        room: GameRoom, type: String?, playerId: String?, card: CardSlot?, from: String?, to: String?,
        effect: PendingEffect?, data: MutableMap<String, Any?>?, logText: String?,
    ): GameEvent {
        var data = data
        val e = GameEvent()
        e.seq = room.eventSeq + 1
        room.eventSeq = e.seq
        e.ts = System.currentTimeMillis()
        e.type = type
        e.playerId = playerId
        if (card != null) {
            e.cardInstanceId = card.instanceId
            e.cardName = card.cardName
            // Entering the field (summoned, played…): it reacts to events from this one on
            if (Zone.FIELD.`is`(to)) card.fieldSeq = e.seq
        }
        e.fromZone = from
        e.toZone = to
        if (effect != null) {
            e.effectId = effect.id
            e.resolutionId = effect.resolutionId
            // A tribute by an effect always says by which card ("si esta carta es tributada por efecto de 1 [bestia]")
            if (GameEvent.TRIBUTE == type && (data == null || !data.containsKey("byCard")) && effect.sourceInstanceId != null) {
                data = if (data == null) HashMap() else HashMap(data)
                data["byCard"] = effect.sourceInstanceId
            }
        }
        e.data = data
        room.timeline.add(e)
        if (room.timeline.size > TIMELINE_LIMIT) room.timeline.removeAt(0)
        room.pendingEvents.add(e)
        refreshContinuous(room)
        if (logText != null) log(room, logText, e.seq)
        return e
    }

    @JvmStatic
    fun log(room: GameRoom, text: String?, seq: Long?) {
        val entry = GameLogEntry(System.currentTimeMillis(), text, seq)
        HistoryDescriber.describe(room, entry)
        room.eventLog.add(entry)
        if (room.eventLog.size > LOG_LIMIT) room.eventLog.removeAt(0)
    }

    /**
     * Rule 2.1 (victory): a player loses when their life stack reaches 0 or when they cannot make the regulatory draw at
     * the start of their turn. Those are the only two ways: an empty deck at any other moment is not a loss.
     */
    @JvmStatic
    fun loseByDeckOut(room: GameRoom, playerId: String?) {
        val loser = room.getPlayer(playerId)
        if (loser != null) endGame(room, loser, "deck", loser.username + " no puede robar al inicio de su turno: pierde la partida")
    }

    @JvmStatic
    fun loseByLives(room: GameRoom, loser: PlayerState?) {
        endGame(room, loser!!, "lives", loser.username + " se quedó sin vidas: pierde la partida")
    }

    /** The game ends at once: nothing pending goes on (combat, chain, triggers), and the room goes to the post-game. */
    @JvmStatic
    fun endGame(room: GameRoom, loser: PlayerState, reason: String, text: String?) {
        record(room, GameEvent.GAME_LOST, loser.userId, null, null, null, null, java.util.Map.of<String, Any?>("reason", reason), text)
        room.winnerId = room.getOpponentId(loser.userId)
        room.endReason = reason
        room.combat = null
        room.chain.clear()
        room.triggerQueue.clear()
        room.pendingEvents.clear()
        room.postGameChoices.clear()
        room.status = GameRoom.RoomStatus.POST_GAME
    }

    /** The event that triggered an effect (null for one activated by its player). */
    @JvmStatic
    fun triggerEvent(room: GameRoom, effect: PendingEffect?): GameEvent? {
        return eventAt(room, effect!!.triggerSeq)
    }

    /** A timeline event by its seq: the timeline is ordered by seq, so it is a binary search. */
    @JvmStatic
    fun eventAt(room: GameRoom, seq: Long?): GameEvent? {
        if (seq == null) return null
        val timeline = room.timeline
        var lo = 0
        var hi = timeline.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val at = timeline[mid].seq
            if (at == seq) return timeline[mid]
            if (at < seq) lo = mid + 1
            else hi = mid - 1
        }
        return null
    }

    /**
     * A card of a player's Línea frontal (rule 4.0.6.2: the Retaguardia cannot be chosen by the opponent's effects), or
     * null when it is no longer there: an effect's target that stopped being valid (rule 11.10.2).
     */
    @JvmStatic
    fun frontLineCard(player: PlayerState?, instanceId: String?): CardSlot? {
        return if (player == null) null else find(frontLine(player.field), instanceId)
    }

    /** A card anywhere in the room, attached ones included. */
    @JvmStatic
    fun findInRoom(room: GameRoom, instanceId: String?): CardSlot? {
        return CardLocator.findInRoom(room, instanceId, CardLocator.ENGINE_ORDER, true)
    }

    /** Every card of the room, in every zone, with the cards attached to them. */
    @JvmStatic
    fun cardsInRoom(room: GameRoom): java.util.stream.Stream<CardSlot> {
        return CardLocator.cards(room, CardLocator.ENGINE_ORDER, true)
    }

    // ------------------------------------------------------------------ Helpers

    @JvmStatic
    fun script(effect: PendingEffect?): EffectScript {
        return CardEffects.byId(effect!!.scriptId!!)
                .orElseThrow { IllegalStateException("Unknown effect " + effect.scriptId) }
    }

    // The card an event is about, where it is now (its zone after the event).
    /** Records that are not things that happen in the game: activating and resolving effects, choosing targets, combat steps. */
    private val BOOKKEEPING: Set<String> = java.util.Set.of(GameEvent.EFFECT_TRIGGERED, GameEvent.EFFECT_ACTIVATED, GameEvent.EFFECT_RESOLVED,
            GameEvent.EFFECT_DECLINED, GameEvent.TARGET, GameEvent.COMBAT_STEP)

    /**
     * Nothing else happened after `event` in its resolution ("lo último que ocurrió"). What happens together with it
     * does not count: the regulatory draw of a summon is part of the same event.
     */
    @JvmStatic
    fun lastThingHappened(room: GameRoom, event: GameEvent): Boolean {
        for (later in room.timeline) {
            if (later.seq <= event.seq || BOOKKEEPING.contains(later.type!!)) continue
            if (event.resolutionId != null && event.resolutionId != later.resolutionId) continue
            if (GameEvent.DRAW == later.type && later.data != null && later.data!!["regulatory"] == true) continue
            return false
        }
        return true
    }

    @JvmStatic
    fun eventCard(room: GameRoom, event: GameEvent?): CardSlot? {
        val owner = if (ownerOf(event!!) != null) ownerOf(event) else event.playerId
        val zone = if (event.toZone != null) event.toZone else "field"
        return findIn(room, owner, zone, event.cardInstanceId)
    }

    @JvmStatic
    fun ownerOf(event: GameEvent): String? {
        return if (event.data == null) null else event.data!!["ownerId"] as String?
    }

    @JvmStatic
    fun strength(card: CardSlot?): Int {
        return (if (card!!.strength == null) 0 else card.strength!!) + card.strengthModifier + card.turnStrength +
                card.laterStrength + card.continuousStrength
    }

    /** Its abilities: printed, inherited from its Recursos, gained this turn and given by its continuous effects now. */
    @JvmStatic
    fun keywords(card: CardSlot?): MutableList<String> {
        val all = ArrayList<String>(card!!.keywords!!)
        for (resource in card.resources!!) all.addAll(resource.inheritKeywords!!)
        all.addAll(card.turnKeywords!!)
        all.addAll(card.laterKeywords!!)
        all.addAll(card.continuousKeywords!!)
        return all
    }

    /**
     * Continuous effects with a condition ("mientras controles 3 o más aliados Rojos") are checked again after every
     * event: each card on the field gets what those that hold give it, and loses what those that stopped gave it.
     */
    @JvmStatic
    fun refreshContinuous(room: GameRoom) {
        for (p in room.players.values) {
            // What cards give their allies ("todos los aliados [armada] ganan fuerza 1")
            val fromAllies = HashMap<String?, Int>()
            for (source in p.field) {
                if (source.faceDown) continue
                for (c in CardEffects.continuousOf(source)) {
                    if (c.targets == null || c.enemies || !c.holds(room, source, p.userId!!)) continue
                    for (ally in p.field) if (c.targets.matches(ally)) fromAllies.merge(ally.instanceId, c.strength) { a, b -> a + b }
                }
            }
            for (card in p.field) {
                var strength = fromAllies.getOrDefault(card.instanceId, 0)
                var level = 0
                val keywords = ArrayList<String>()
                for (c in CardEffects.continuousOf(card)) {
                    if (c.targets != null || !Zone.FIELD.`is`(c.zone)) continue
                    if (card.faceDown || !c.holds(room, card, p.userId!!)) continue
                    strength += c.strength + (if (c.bonus == null) 0 else c.bonus.of(room, p.userId))
                    level += if (c.levelBonus == null) 0 else c.levelBonus.of(room, p.userId)
                    keywords.addAll(c.keywords!!)
                }
                // "Todos tus aliados … ganan …" on this side of the table
                for (g in room.fieldGrants) {
                    if (g.controllerId == p.userId && !card.faceDown && g.filter!!.matches(card)
                            && card.instanceId != g.exceptId) keywords.add(g.keyword!!)
                }
                card.continuousStrength = strength
                card.continuousLevel = level
                card.continuousKeywords = keywords
            }
        }
        // Then what cards do to their enemies, with the enemies' strength already worked out
        for (p in room.players.values) {
            val opponent = room.getPlayer(room.getOpponentId(p.userId)) ?: continue
            for (source in p.field) {
                if (source.faceDown) continue
                for (c in CardEffects.continuousOf(source)) {
                    if (c.targets == null || !c.enemies) continue
                    // A creature in the Retaguardia cannot affect enemies, and a creature in theirs is out of reach (rule 4.0.6.2; designer ruling 2026-11-01)
                    if ("creature" == source.cardType && !inFrontLine(source)) continue
                    if (!c.holds(room, source, p.userId!!)) continue
                    for (enemy in opponent.field) {
                        if (!c.targets.matches(enemy) || !inFrontLine(enemy)) continue
                        val keywords = ArrayList<String>(enemy.continuousKeywords!!)
                        keywords.addAll(c.keywords!!)
                        enemy.continuousKeywords = keywords
                        enemy.continuousStrength = enemy.continuousStrength + c.strength
                    }
                }
            }
        }
    }

    @JvmRecord
    data class Located(val card: CardSlot?, val zone: String?)

    @JvmStatic
    fun locate(player: PlayerState?, instanceId: String?): Located? {
        val found = CardLocator.locate(player, instanceId, CardLocator.ENGINE_ORDER, false)
        return if (found == null) null else Located(found.card, found.zone!!.id())
    }

    @JvmStatic
    fun findAnywhere(room: GameRoom, playerId: String?, instanceId: String?): CardSlot? {
        val p = room.getPlayer(playerId)
        val at = if (p == null) null else locate(p, instanceId)
        return at?.card
    }

    /** The player's cards in that zone; none for an unknown one. */
    @JvmStatic
    fun zone(player: PlayerState?, zone: String?): MutableList<CardSlot> {
        return Zone.parse(zone).map { z -> z.of(player!!) }.orElse(java.util.List.of())
    }

    @JvmStatic
    fun zoneName(zone: String?): String? {
        return Zone.shortLabel(zone)
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun ids(cards: List<CardSlot>?): List<String> {
        return cards!!.map { it.instanceId } as List<String>
    }

    @JvmStatic
    fun find(cards: List<CardSlot>?, instanceId: String?): CardSlot? {
        if (instanceId == null) return null
        return cards!!.firstOrNull { c -> instanceId == c.instanceId }
    }

    @JvmStatic
    fun findIn(room: GameRoom, playerId: String?, zone: String?, instanceId: String?): CardSlot? {
        val p = if (playerId == null) null else room.getPlayer(playerId)
        return if (p == null || zone == null) null else find(EffectEngine.zone(p, zone), instanceId)
    }

    @JvmStatic
    fun concat(a: List<CardSlot>?, b: List<CardSlot>?): MutableList<CardSlot> {
        val all = ArrayList(a!!)
        all.addAll(b!!)
        return all
    }

    @JvmStatic
    fun concatStrings(a: List<String>?, b: List<String>?): MutableList<String> {
        val all = ArrayList<String>(a ?: java.util.List.of())
        if (b != null) all.addAll(b)
        return all
    }

    @JvmStatic
    fun name(card: CardSlot?): String {
        return if (card!!.cardName != null) card.cardName!! else "una carta"
    }

    /** The cards moved, for the history when they were public (": A, B y C"); nothing when there are none. */
    @JvmStatic
    fun names(cards: List<CardSlot>?): String {
        val names = cards!!.map { name(it) }
        if (names.isEmpty()) return ""
        if (names.size == 1) return ": " + names[0]
        return ": " + names.subList(0, names.size - 1).joinToString(", ") + " y " + names[names.size - 1]
    }

    /** Zones whose cards everybody sees (what leaves them can be named). */
    @JvmStatic
    fun publicZone(zone: String?): Boolean {
        return Zone.TRIBUTE_ZONE.`is`(zone) || Zone.DISCARD_PILE.`is`(zone) || Zone.FIELD.`is`(zone)
    }

    @JvmStatic
    fun plural(n: Int, one: String, many: String): String {
        return if (n == 1) one else many
    }

    @JvmStatic
    fun gemsText(n: Int): String {
        return n.toString() + " " + plural(n, "gema", "gemas")
    }

    @JvmStatic
    fun clamp(value: Int): Int {
        return Math.max(-10, Math.min(10, value))
    }

    @JvmStatic
    fun inFrontLine(card: CardSlot?): Boolean {
        val y = card!!.y
        return y != null && y < 50
    }

    @JvmStatic
    fun frontLine(field: List<CardSlot>?): List<CardSlot> {
        return field!!.filter { inFrontLine(it) }
    }

    /** The player's cards on the field (Línea frontal and Retaguardia) that match. */
    @JvmStatic
    fun countOnField(room: GameRoom, playerId: String?, filter: CardFilter?): Long {
        val player = room.getPlayer(playerId) ?: return 0
        val f = filter!!
        return player.field.count { f.matches(it) }.toLong()
    }

    /** The player's cards in one of their zones that match (none for an unknown zone). */
    @JvmStatic
    fun countInZone(room: GameRoom, playerId: String?, zone: String?, filter: CardFilter?): Long {
        val player = room.getPlayer(playerId) ?: return 0
        val cards = EffectEngine.zone(player, zone)
        val f = filter!!
        return cards.count { f.matches(it) }.toLong()
    }

    /** The sum of the levels of the player's creatures on the field that match. */
    @JvmStatic
    fun levelsOnField(room: GameRoom, playerId: String?, filter: CardFilter?): Int {
        val player = room.getPlayer(playerId) ?: return 0
        return player.field.filter { c -> "creature" == c.cardType && filter!!.matches(c) }
                .sumOf { c -> if (c.getLevel() == null) 0 else c.getLevel()!! }
    }

    /** The card cannot be targeted by an effect of an opponent's card of that level ("Inmune", "Inmune 4"). */
    @JvmStatic
    fun immuneTo(card: CardSlot?, sourceLevel: Int): Boolean {
        for (k in keywords(card)) {
            if (!k.startsWith(IMMUNE)) continue
            val m = Regex("(\\d+)").find(k)
            if (m == null || sourceLevel <= m.groupValues[1].toInt()) return true
        }
        return false
    }

    /** How many lives the player has. */
    @JvmStatic
    fun livesOf(room: GameRoom, playerId: String?): Int {
        val player = room.getPlayer(playerId)
        return if (player == null) 0 else player.lifeStack.size
    }

    /** Gems the player has on the shared Contador (rule 4.0.9): positive on their side. */
    @JvmStatic
    fun gemsOf(room: GameRoom, userId: String?): Int {
        return room.sharedCounter * direction(room, userId)
    }

    /** Same convention as the room: the first player counts the Contador negatively. */
    @JvmStatic
    fun direction(room: GameRoom, userId: String?): Int {
        val firstId = room.players.keys.iterator().next()
        return if (firstId == userId) -1 else 1
    }

    /**
     * Rule 5.0.0.1: on your turn, paying may not leave the opponent with more gems than their Marcador shows.
     * On the opponent's turn that limit does not apply: only the Contador's end (10 gems for the opponent).
     * Paying nothing does not move the Contador, so a cost of 0 can always be paid.
     */
    @JvmStatic
    fun canPay(room: GameRoom, userId: String?, cost: Int): Boolean {
        if (cost <= 0) return true
        if (userId!! != room.currentTurnUserId) return gemsOf(room, userId) - cost >= -10
        val opponent = room.getPlayer(room.getOpponentId(userId))
        val opponentMarker = if (opponent == null) 0 else opponent.resourceCount
        return gemsOf(room, userId) - cost >= -opponentMarker
    }

    @JvmStatic
    fun pay(room: GameRoom, userId: String?, cost: Int) {
        room.sharedCounter = clamp(room.sharedCounter - cost * direction(room, userId))
    }

    @JvmStatic
    fun rulesPrompt(
        room: GameRoom, playerId: String?, purpose: String?, title: String?, instruction: String?,
        chosenLabel: String?, options: List<CardSlot>?, shown: List<CardSlot>?,
    ) {
        val p = Prompt()
        p.id = room.nextId("prompt")
        p.kind = Prompt.Kind.PICK
        p.purpose = purpose
        p.playerId = playerId
        p.sourceName = title
        p.title = title
        p.instruction = instruction
        p.chosenLabel = chosenLabel
        p.cards = ArrayList(shown!!)
        p.validIds = ArrayList(ids(options))
        p.min = 0
        p.max = 1
        room.prompt = p
    }

    /**
     * The value of a keyword the card has, printed or gained this turn: 0 when it has no number ("Provocar"),
     * null when it does not have it.
     */
    @JvmStatic
    fun keywordLevel(card: CardSlot?, keyword: String?): Int? {
        var best: Int? = null
        for (k in keywords(card)) {
            if (!k.lowercase().startsWith(keyword!!.lowercase())) continue
            val m = Regex("(\\d+)").find(k)
            val value = if (m != null) m.groupValues[1].toInt() else 0
            best = if (best == null) value else Math.max(best, value)
        }
        return best
    }

    /**
     * Whether a card can be reactivated: an exhausted creature. Effects that forbid reactivating a creature
     * will be checked here once a card has one.
     */
    @JvmStatic
    fun reactivable(card: CardSlot?): Boolean {
        return "creature" == card!!.cardType && card.tapped && card.noUntap != 1
    }

    @JvmStatic
    fun allies(player: PlayerState?): List<CardSlot> {
        return player!!.field.filter { c -> "creature" == c.cardType }
    }

    /** Notes what an effect gave or took from a card for a while, for its details. */
    @JvmStatic
    fun floating(card: CardSlot?, effect: PendingEffect?, text: String?, until: String?, strength: Int, keyword: String?) {
        card!!.floating!!.add(FloatingEffect(text, effect?.sourceName, until, strength, keyword))
    }
}
