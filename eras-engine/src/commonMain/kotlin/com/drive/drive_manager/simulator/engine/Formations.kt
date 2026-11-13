package com.drive.drive_manager.simulator.engine

import kotlin.jvm.JvmStatic
import com.drive.drive_manager.simulator.engine.EffectEngine.findInRoom
import com.drive.drive_manager.simulator.engine.EffectEngine.inFrontLine
import com.drive.drive_manager.simulator.engine.EffectEngine.log
import com.drive.drive_manager.simulator.engine.EffectEngine.name
import com.drive.drive_manager.simulator.engine.EffectEngine.record
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState

/**
 * Enlace (reglas en desarrollo, designer 2026-10-25 and 2026-11-13): formations of the Línea frontal and the link effects their members
 * give each other.
 *
 * - The Línea frontal has an order: its creatures by `x` (ties by their place in the field), [ordered]. Only effects move a creature
 *   ([swap]); one that enters takes the position its player chooses ([placeAt] spreads the `x` again, no ties).
 * - A points to B when they are next to each other, A has the arrow toward B ("left", "right") and A is active (exhausted: no arrows).
 * - Admission, checked only when a creature is put in ([canAdmit]): at its position it points to, or is pointed at by, a member, and the
 *   formation's size then is at most the highest current level among its members and the creature. Two creatures that are in none form
 *   one the same way. A formation then stays as it is when arrows or levels change; a member leaves it only by leaving the Línea frontal
 *   (the row closes up), one of fewer than 2 is none, and its player's formations are undone at the start of their turn ([disband]).
 * - An active member gives its link effect (the card file's "formation" section) to the members of its formation next to it that it
 *   points to ([refresh] keeps them in each card's `formationGrants`). The effects are run as the receiver's own ("esta carta" is the
 *   receiver), by [CardEffects.of], [CardEffects.continuousOf] and [CardEffects.replacementsOf]; their ids carry the source and since
 *   when ([VIA]), so a relation broken and restored is a new one (its "una vez por turno" is fresh) and a swap that keeps it is not.
 */
object Formations {

    const val LEFT = "left"
    const val RIGHT = "right"

    /** In the id of a granted effect: "<id of the link effect>~via:<source instance>@<since>". */
    const val VIA = "~via:"

    // ------------------------------------------------------------------ The Línea frontal

    /** The player's creatures of the Línea frontal, left to right. */
    @JvmStatic
    fun ordered(player: PlayerState): List<CardSlot> {
        val front = ArrayList<IndexedValue<CardSlot>>()
        player.field.forEachIndexed { i, c -> if ("creature" == c.cardType && inFrontLine(c)) front.add(IndexedValue(i, c)) }
        return front.sortedWith(compareBy<IndexedValue<CardSlot>>({ it.value.x ?: 50.0 }, { it.index })).map { it.value }
    }

    /** Puts the card at that position of the Línea frontal (0: leftmost), the others keeping their order; the row is spread again. */
    @JvmStatic
    fun placeAt(player: PlayerState, card: CardSlot, index: Int) {
        val order = ArrayList(ordered(player).filter { it !== card })
        order.add(index.coerceIn(0, order.size), card)
        spread(order)
    }

    private fun spread(order: List<CardSlot>) {
        val n = order.size
        order.forEachIndexed { i, c -> c.x = ((i + 1) * 1000 / (n + 1)) / 10.0 }
    }

    /** `from` points to `to` in that order: next to each other, the arrow toward it printed, and `from` active. */
    @JvmStatic
    fun pointsTo(order: List<CardSlot>, from: CardSlot, to: CardSlot): Boolean {
        if (from.tapped || from.faceDown) return false
        val a = order.indexOf(from)
        val b = order.indexOf(to)
        if (a < 0 || b < 0) return false
        val arrows = from.arrows ?: return false
        return (b == a + 1 && arrows.contains(RIGHT)) || (b == a - 1 && arrows.contains(LEFT))
    }

    /** The formation the creature is in, or null. */
    @JvmStatic
    fun formationOf(player: PlayerState, instanceId: String?): MutableList<String>? {
        if (instanceId == null) return null
        return player.formations.firstOrNull { it.contains(instanceId) }
    }

    /** The members of its formation next to it (left, right), none when it is in none. */
    @JvmStatic
    fun adjacentMembers(player: PlayerState, card: CardSlot): List<CardSlot> {
        val formation = formationOf(player, card.instanceId) ?: return emptyList()
        val order = ordered(player)
        val at = order.indexOf(card)
        if (at < 0) return emptyList()
        return listOfNotNull(order.getOrNull(at - 1), order.getOrNull(at + 1)).filter { formation.contains(it.instanceId) }
    }

    /** The player of the room whose field has the card, or null. */
    @JvmStatic
    fun ownerOf(room: GameRoom, card: CardSlot): PlayerState? = room.players.values.firstOrNull { p -> p.field.any { it === card } }

    /**
     * Admission (checked only when it is put in): with the card at its place in `order`, a member next to it points to it or is pointed
     * at by it, and the formation's size then is at most the highest current level among the members and the card.
     */
    @JvmStatic
    fun canAdmit(order: List<CardSlot>, members: List<String>, card: CardSlot): Boolean {
        val at = order.indexOf(card)
        if (at < 0 || members.contains(card.instanceId)) return false
        val next = listOfNotNull(order.getOrNull(at - 1), order.getOrNull(at + 1)).filter { members.contains(it.instanceId) }
        if (next.none { pointsTo(order, it, card) || pointsTo(order, card, it) }) return false
        var highest = card.getLevel() ?: 0
        for (c in order) if (members.contains(c.instanceId)) highest = maxOf(highest, c.getLevel() ?: 0)
        return members.size + 1 <= highest
    }

    // ------------------------------------------------------------------ After every event

    /**
     * Checked again after every event (EffectEngine.refreshContinuous, first): what entered each Línea frontal (its chance to link
     * comes once nothing is pending), the members that left it, and the link effects each creature receives now.
     */
    @JvmStatic
    fun refresh(room: GameRoom) {
        for (p in room.players.values) {
            val order = ordered(p)
            noteEntries(room, p, order)
            if (!p.formations.isEmpty()) dropLeavers(room, p)
            for (c in p.field) {
                val grants = if (inFrontLine(c) && "creature" == c.cardType) grantsOf(room, p, order, c) else null
                if (grants == null || grants.isEmpty()) {
                    if (c.formationGrants != null && !c.formationGrants!!.isEmpty()) c.formationGrants = ArrayList()
                } else if (grants != c.formationGrants) c.formationGrants = grants
            }
        }
    }

    /** A card in the middle of a move (taken from its zone, not yet in the other): still where it was. */
    private fun inTransit(room: GameRoom, id: String): Boolean = findInRoom(room, id) == null

    private fun noteEntries(room: GameRoom, p: PlayerState, order: List<CardSlot>) {
        val ids = order.map { it.instanceId!! }
        val seen = p.frontSeen
        if (seen == null) {
            p.frontSeen = ArrayList(ids)
            return
        }
        if (seen.size == ids.size && seen.containsAll(ids)) return
        // Only where Enlace is played: a creature with arrows on that line, or a formation
        val relevant = !p.formations.isEmpty() || order.any { !it.arrows.isNullOrEmpty() }
        if (relevant) for (id in ids) if (!seen.contains(id) && !p.formationEntries.contains(id)) p.formationEntries.add(id)
        val now = ArrayList(ids)
        for (id in seen) if (!now.contains(id) && inTransit(room, id)) now.add(id)
        p.frontSeen = now
    }

    /** Members no longer in the Línea frontal leave their formation; one of fewer than 2 is none. */
    private fun dropLeavers(room: GameRoom, p: PlayerState) {
        for (f in p.formations) {
            f.removeAll { id ->
                val c = p.field.firstOrNull { it.instanceId == id }
                if (c != null) !inFrontLine(c) else !inTransit(room, id)
            }
        }
        p.formations.removeAll { it.size < 2 }
    }

    /** "source|since|name" of each member of its formation that points to it now; a relation already there keeps its since. */
    private fun grantsOf(room: GameRoom, p: PlayerState, order: List<CardSlot>, card: CardSlot): MutableList<String>? {
        val formation = formationOf(p, card.instanceId) ?: return null
        val at = order.indexOf(card)
        val out = ArrayList<String>()
        for (source in listOfNotNull(order.getOrNull(at - 1), order.getOrNull(at + 1))) {
            if (!formation.contains(source.instanceId) || !pointsTo(order, source, card)) continue
            val before = card.formationGrants?.firstOrNull { it.startsWith(source.instanceId + "|") }
            out.add(before ?: (source.instanceId + "|" + room.eventSeq + "|" + (source.cardName ?: "")))
        }
        return out
    }

    /** A granted effect's source instance, since and card name ("source|since|name"). */
    data class Grant(val sourceId: String, val since: String, val sourceName: String)

    @JvmStatic
    fun grants(entries: List<*>?): List<Grant> {
        if (entries == null) return emptyList()
        return entries.mapNotNull { e ->
            val parts = (e as? String)?.split("|", limit = 3)
            if (parts == null || parts.size < 3) null else Grant(parts[0], parts[1], parts[2])
        }
    }

    /** The id a granted copy of a link effect has. */
    @JvmStatic
    fun grantedId(baseId: String?, grant: Grant): String = baseId + VIA + grant.sourceId + "@" + grant.since

    // ------------------------------------------------------------------ Entering the Línea frontal

    /**
     * The options of a creature that entered the Línea frontal: "pos:i" (that position, no formation) and "pos:i:id" (that position,
     * linking with the creature `id`: its formation, or a new one with it). Between two members of one formation it can only go
     * joining it.
     */
    @JvmStatic
    fun entryOptions(player: PlayerState, card: CardSlot): List<String> {
        val others = ordered(player).filter { it !== card }
        val out = ArrayList<String>()
        for (i in 0..others.size) {
            val left = others.getOrNull(i - 1)
            val right = others.getOrNull(i)
            val order = ArrayList(others).also { it.add(i, card) }
            val leftFormation = formationOf(player, left?.instanceId)
            if (leftFormation != null && leftFormation === formationOf(player, right?.instanceId)) {
                if (canAdmit(order, leftFormation, card)) out.add("pos:$i:" + left!!.instanceId)
                continue
            }
            out.add("pos:$i")
            for (n in listOfNotNull(left, right)) {
                if (canAdmit(order, formationOf(player, n.instanceId) ?: listOf(n.instanceId!!), card)) out.add("pos:$i:" + n.instanceId)
            }
        }
        return out
    }

    /** The card stands between two members of one formation it is not in (where it cannot be). */
    private fun splits(player: PlayerState, card: CardSlot): Boolean {
        val order = ordered(player)
        val at = order.indexOf(card)
        val left = formationOf(player, order.getOrNull(at - 1)?.instanceId)
        return left != null && left === formationOf(player, order.getOrNull(at + 1)?.instanceId) && !left.contains(card.instanceId)
    }

    @JvmStatic
    fun hasEntries(room: GameRoom): Boolean = room.players.values.any { !it.formationEntries.isEmpty() }

    /**
     * The next creature that entered the Línea frontal: its player chooses its position and whether it links (FORMATION_ENTRY), when
     * there is a link to make; otherwise it stays where it is (at the end of the row if it was left between two members of a formation).
     */
    @JvmStatic
    fun offerEntry(room: GameRoom) {
        val player = room.players.values.first { !it.formationEntries.isEmpty() }
        val card = player.field.firstOrNull { it.instanceId == player.formationEntries[0] }
        player.formationEntries.removeAt(0)
        if (card == null || !inFrontLine(card) || "creature" != card.cardType || formationOf(player, card.instanceId) != null) return
        val options = entryOptions(player, card)
        if (options.none { it.count { ch -> ch == ':' } == 2 }) {
            if (splits(player, card)) placeAt(player, card, ordered(player).size)
            return
        }
        val p = Prompt()
        p.id = room.nextId("prompt")
        p.kind = Prompt.Kind.PICK
        p.purpose = PromptPurpose.FORMATION_ENTRY.id()
        p.playerId = player.userId
        p.sourceName = name(card)
        p.sourceImageUrl = card.imageUrl
        p.title = name(card) + " · Línea frontal"
        p.instruction = "Elige la posición de " + name(card) + " en tu línea frontal y si se enlaza a una formación, o déjala donde está."
        p.chosenLabel = "Colocar"
        p.cards = ArrayList(ordered(player))
        p.validIds = ArrayList(options)
        p.subjectId = card.instanceId
        p.min = 0
        p.max = 1
        val labels = LinkedHashMap<String, String>()
        val others = ordered(player).filter { it !== card }
        for (o in options) labels[o] = optionText(player, others, o)
        p.cardLabels = labels
        room.prompt = p
    }

    private fun optionText(player: PlayerState, others: List<CardSlot>, option: String): String {
        val parts = option.split(":")
        val at = parts[1].toInt()
        val where = "Posición " + (at + 1) + " de " + (others.size + 1)
        if (parts.size < 3) return "$where, sin formación"
        val with = others.firstOrNull { it.instanceId == parts[2] }
        return if (formationOf(player, parts[2]) != null) "$where, se une a la formación de " + name(with)
        else "$where, forma una formación con " + name(with)
    }

    /** The answer to FORMATION_ENTRY: the position and the link chosen; none keeps it where it is. */
    @JvmStatic
    fun answerEntry(room: GameRoom, player: PlayerState, prompt: Prompt, picks: List<String>) {
        val card = player.field.firstOrNull { it.instanceId == prompt.subjectId } ?: return
        if (!inFrontLine(card)) return
        if (picks.isEmpty() || !entryOptions(player, card).contains(picks[0])) {
            if (splits(player, card)) placeAt(player, card, ordered(player).size)
            return
        }
        val parts = picks[0].split(":")
        placeAt(player, card, parts[1].toInt())
        if (parts.size < 3) return
        val with = player.field.firstOrNull { it.instanceId == parts[2] } ?: return
        join(room, player, card, with)
    }

    /** The card links with `with`: it joins its formation, or both form one. */
    @JvmStatic
    fun join(room: GameRoom, player: PlayerState, card: CardSlot, with: CardSlot) {
        val formation = formationOf(player, with.instanceId)
        if (formation != null) formation.add(card.instanceId!!)
        else player.formations.add(arrayListOf(with.instanceId!!, card.instanceId!!))
        record(room, GameEvent.FORMATION, player.userId, card, null, null, null, frozenMapOf<String, Any?>("with", with.instanceId),
            if (formation != null) name(card) + " se une a la formación de " + name(with) else name(card) + " y " + name(with) + " forman una formación")
    }

    // ------------------------------------------------------------------ In the main phase

    /** In the player's own main phase, with nothing pending, each creature of theirs in no formation and what it can link with. */
    @JvmStatic
    fun joinOptions(room: GameRoom, userId: String?): MutableMap<String, MutableList<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        val player = room.getPlayer(userId) ?: return out
        if (room.status != GameRoom.RoomStatus.IN_PROGRESS || userId != room.currentTurnUserId || "MAIN" != room.currentPhase
            || room.isEngineBusy()) return out
        val order = ordered(player)
        for ((i, c) in order.withIndex()) {
            if (formationOf(player, c.instanceId) != null) continue
            val targets = ArrayList<String>()
            for (n in listOfNotNull(order.getOrNull(i - 1), order.getOrNull(i + 1))) {
                if (canAdmit(order, formationOf(player, n.instanceId) ?: listOf(n.instanceId!!), c)) targets.add(n.instanceId!!)
            }
            if (!targets.isEmpty()) out[c.instanceId!!] = targets
        }
        return out
    }

    /** FORMATION_JOIN: the creature links with the one next to it (its formation, or a new one with it). */
    @JvmStatic
    fun joinByPlayer(room: GameRoom, userId: String?, instanceId: String?, targetId: String?): String? {
        if (room.status != GameRoom.RoomStatus.IN_PROGRESS) return "La partida no está en curso"
        if (userId != room.currentTurnUserId) return "Solo puedes enlazar criaturas en tu turno"
        if ("MAIN" != room.currentPhase) return "Solo puedes enlazar criaturas en tu fase principal"
        if (room.isEngineBusy()) return if (room.combat != null) "Hay un combate en curso" else "Hay un efecto resolviéndose"
        val player = room.getPlayer(userId) ?: return "No estás en la partida"
        val options = joinOptions(room, userId)[instanceId]
        if (options == null || !options.contains(targetId)) return "Esas criaturas no pueden enlazarse ahora"
        val card = player.field.first { it.instanceId == instanceId }
        val with = player.field.first { it.instanceId == targetId }
        join(room, player, card, with)
        Chain.advance(room)
        return null
    }

    // ------------------------------------------------------------------ Rules that move them

    /** The start of the player's turn: their formations are undone (they may form them again). */
    @JvmStatic
    fun disband(room: GameRoom, player: PlayerState) {
        if (player.formations.isEmpty()) return
        player.formations.clear()
        log(room, "Se desarman las formaciones de " + player.username, null)
    }

    /**
     * A creature special summoned in another's place (Ascenso, and any special summon that replaces its summoner) takes its position and
     * its formation, whatever the formation's conditions are now (designer, 2026-11-13).
     */
    @JvmStatic
    fun inherit(room: GameRoom, target: CardSlot, summoner: CardSlot) {
        val player = ownerOf(room, summoner) ?: return
        val from = summoner.instanceId ?: return
        val to = target.instanceId ?: return
        for (f in player.formations) {
            val at = f.indexOf(from)
            if (at >= 0) f[at] = to
        }
        // Its place in the row: the summoner's `x`, which it takes, must be its own (no ties, which the field list's order breaks)
        val order = ordered(player)
        if (order.contains(summoner) && (!player.formations.isEmpty() || order.any { !it.arrows.isNullOrEmpty() })) spread(order)
        val seen = player.frontSeen
        if (seen != null && inFrontLine(summoner)) {
            seen.remove(from)
            if (!seen.contains(to)) seen.add(to)
        }
    }

    /** An effect swaps the positions of two creatures of the Línea frontal (their formations stay as they are). */
    @JvmStatic
    fun swap(room: GameRoom, effect: PendingEffect?, player: PlayerState, a: CardSlot, b: CardSlot) {
        val order = ArrayList(ordered(player))
        val i = order.indexOf(a)
        val j = order.indexOf(b)
        if (i < 0 || j < 0 || i == j) return
        order[i] = b
        order[j] = a
        spread(order)
        record(room, GameEvent.POSITION_SWAP, player.userId, a, null, null, effect, frozenMapOf<String, Any?>("with", b.instanceId),
            name(a) + " y " + name(b) + " intercambian sus posiciones" + (if (effect != null) " (" + effect.sourceName + ")" else ""))
    }

    /** Two allies in the same formation: what "intercambia de posición … en la misma formación" may choose. */
    @JvmStatic
    fun sameFormation(player: PlayerState, a: CardSlot, b: CardSlot): Boolean {
        val f = formationOf(player, a.instanceId)
        return a !== b && f != null && f.contains(b.instanceId)
    }

    /** The creatures of the player in some formation. */
    @JvmStatic
    fun members(player: PlayerState): List<CardSlot> = ordered(player).filter { formationOf(player, it.instanceId) != null }

    /** The size of the formation the creature is in (0: none). */
    @JvmStatic
    fun sizeOf(player: PlayerState, card: CardSlot): Int = formationOf(player, card.instanceId)?.size ?: 0
}
