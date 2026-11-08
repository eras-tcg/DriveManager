package com.drive.drive_manager.simulator.actions

import kotlin.math.min
import com.drive.drive_manager.simulator.engine.GameRandom
import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.GameStateUpdate
import com.drive.drive_manager.simulator.room.PlayerState
import com.drive.drive_manager.simulator.room.SimMessages
import com.drive.drive_manager.simulator.room.Zone

/** Moving cards between zones by hand (editor mode), and looking at them. */
object ZoneActions {

    @JvmStatic
    fun handleMove(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val tp = action.getString("targetPlayerId")
        val player = room.getPlayer(tp ?: userId) ?: return GameStateUpdate.notInRoom(room)

        val instanceId = action.getString("instanceId")
        val fromZone   = action.getString("fromZone")
        val toZone     = action.getString("toZone")
        val x          = action.getDouble("x")
        val y          = action.getDouble("y")

        val from = getZone(player, fromZone)
        val to   = getZone(player, toZone)
        if (from == null || to == null) return GameStateUpdate.error(room.roomId, SimMessages.INVALID_ZONE)

        val card = from.firstOrNull { c -> instanceId!! == c.instanceId }
                ?: return GameStateUpdate.error(room.roomId, SimMessages.CARD_NOT_FOUND)

        from.remove(card)
        val wasPublic = !card.faceDown

        if (Zone.FIELD.`is`(toZone)) {
            card.x = x
            card.y = y
            // Placed by hand: it reacts to events from now on
            if (!Zone.FIELD.`is`(fromZone)) card.fieldSeq = room.eventSeq + 1
        } else {
            card.x = null
            card.y = null
        }

        // When leaving field, send attached cards to discard
        if (Zone.FIELD.`is`(fromZone) && !Zone.FIELD.`is`(toZone)) {
            card.materials!!.forEach { m -> m.faceDown = false; m.tapped = false; player.discardPile.add(m) }
            card.resources!!.forEach { r -> r.faceDown = false; r.tapped = false; player.discardPile.add(r) }
            card.materials = ArrayList()
            card.resources = ArrayList()
            // A creature removed from the field loses its floating effects (what is decided below keeps its face)
            val faceDown = card.faceDown
            com.drive.drive_manager.simulator.engine.Removals.leaveField(card)
            card.faceDown = faceDown
        }

        if (Zone.DISCARD_PILE.`is`(toZone) || Zone.TRIBUTE_ZONE.`is`(toZone)) {
            card.faceDown = false
        }
        if (Zone.LIFE_STACK.`is`(fromZone) && !Zone.LIFE_STACK.`is`(toZone)) {
            card.faceDown = false
        }
        if (Zone.DECK.`is`(fromZone) && !Zone.DECK.`is`(toZone) && !Zone.LIFE_STACK.`is`(toZone)) {
            card.faceDown = false
        }
        if (Zone.DECK.`is`(toZone)) {
            card.faceDown = true
        }
        if (Zone.HAND.`is`(fromZone) && Zone.LIFE_STACK.`is`(toZone)) {
            card.faceDown = true
        }
        val forceFaceDown = action.payload!!["faceDown"]
        if (forceFaceDown == true) {
            card.faceDown = true
        }
        // Hand cards are always visible to their owner — hiding from the
        // OPPONENT is already handled unconditionally by zone type (hand is
        // never shown for isOpponent), so faceDown must never stick here even
        // if a "hide from opponent" send explicitly requested it above.
        if (Zone.HAND.`is`(toZone)) {
            card.faceDown = false
        }
        if (!Zone.FIELD.`is`(toZone)) {
            card.tapped = false
        }

        val toPosition = action.getString("toPosition")
        if (Zone.DECK.`is`(toZone)) {
            if ("top" == toPosition) {
                to.add(0, card)
            } else {
                to.add(card)
            }
        } else if (Zone.LIFE_STACK.`is`(toZone) && "top" == toPosition) {
            to.add(0, card)
        } else {
            to.add(card)
        }

        val update = GameStateUpdate.from(room, ActionType.MOVE_CARD, userId)
        update.logEntry = buildMoveLogEntry(room, player, card, fromZone, toZone, toPosition, wasPublic)
        return update
    }

    @JvmStatic
    fun buildMoveLogEntry(room: GameRoom, player: PlayerState, card: CardSlot, fromZone: String?, toZone: String?, toPosition: String?, wasPublic: Boolean): String? {
        val who   = player.username
        val label = cardLabel(card)
        val from  = zoneLabel(fromZone)

        when (Zone.parse(toZone)) {
            Zone.FIELD -> {
                // A creature entering the field from a non-field zone while the game is
                // in progress always triggers the effect-summon draw/no-draw prompt
                // (see FieldZone.vue's effectSummonPending) — so its placement is only
                // logged once that decision resolves, via handleEffectSummonResolve.
                val pendingDecision = "creature".equals(card.cardType, ignoreCase = true)
                        && !Zone.FIELD.`is`(fromZone)
                        && room.status == GameRoom.RoomStatus.IN_PROGRESS
                if (pendingDecision) return null
                val suffix = if ("creature".equals(card.cardType, ignoreCase = true) && card.faceDown) " (set)" else ""
                return who + " colocó/invocó " + label + " desde " + from + suffix
            }
            Zone.LIFE_STACK -> {
                val pos = if ("top" == toPosition) "tope" else "fondo"
                return who + " añadió " + (if (card.faceDown) "una carta oculta" else label) + " al " + pos + " de la vida desde " + from
            }
            Zone.HAND -> {
                val fromPublicZone = Zone.FIELD.`is`(fromZone) || Zone.TRIBUTE_ZONE.`is`(fromZone) || Zone.DISCARD_PILE.`is`(fromZone)
                val what = if (fromPublicZone && !card.faceDown) label else "una carta"
                return who + " añadió " + what + " a la mano desde " + from
            }
            Zone.TRIBUTE_ZONE ->
                return who + " envió " + (if (card.faceDown) "una carta" else label) + " a Tributo desde " + from
            Zone.DISCARD_PILE ->
                return who + " envió " + (if (card.faceDown) "una carta" else label) + " a Descarte desde " + from
            Zone.DECK -> {
                val pos = if ("top" == toPosition) "tope" else "fondo"
                val what = if (wasPublic) label else "una carta oculta"
                return who + " envió " + what + " al " + pos + " del mazo desde " + from
            }
            else ->
                return null
        }
    }

    @JvmStatic
    fun cardLabel(card: CardSlot): String {
        return if (card.cardName != null) card.cardName!! else "Carta"
    }

    @JvmStatic
    fun zoneLabel(zone: String?): String? {
        return Zone.logLabel(zone)
    }

    @JvmStatic
    fun handleFlip(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)

        val instanceId = action.getString("instanceId")
        val zoneName   = action.getString("zone")
        val zone = getZone(player, zoneName) ?: return GameStateUpdate.error(room.roomId, SimMessages.INVALID_ZONE)

        val update = GameStateUpdate.from(room, ActionType.FLIP_CARD, userId)
        zone.firstOrNull { c -> instanceId!! == c.instanceId }
                ?.let { c ->
                    val nowFaceDown = !c.faceDown
                    c.faceDown = nowFaceDown
                    if (Zone.FIELD.`is`(zoneName) && nowFaceDown) {
                        update.logEntry = player.username + " puso boca abajo a " + cardLabel(c) + " en el campo"
                    } else if (Zone.LIFE_STACK.`is`(zoneName) && !nowFaceDown) {
                        update.logEntry = player.username + " reveló " + cardLabel(c) + " en su vida"
                    } else if (Zone.LIFE_STACK.`is`(zoneName)) {
                        update.logEntry = player.username + " ocultó a " + cardLabel(c) + " en su vida"
                    } else if (Zone.TRIBUTE_ZONE.`is`(zoneName) || Zone.DISCARD_PILE.`is`(zoneName)) {
                        val zoneWord = if (Zone.TRIBUTE_ZONE.`is`(zoneName)) "Tributo" else "Descarte"
                        update.logEntry = (player.username + (if (nowFaceDown) " ocultó a " else " reveló a ")
                                + cardLabel(c) + " en " + zoneWord)
                    }
                }

        return update
    }

    @JvmStatic
    fun handleShuffle(room: GameRoom, userId: String?): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        GameRandom.shuffle(player.deck, room.random)
        val update = GameStateUpdate.from(room, ActionType.SHUFFLE_DECK, userId)
        update.logEntry = player.username + " barajó su mazo"
        return update
    }

    /** Broadcast-only: the action of peeking is public, the cards seen are not. */
    @JvmStatic
    fun handleViewDeck(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        val position = action.getString("position")
        val n = action.getInt("n", 1)
        val update = GameStateUpdate.from(room, ActionType.VIEW_DECK, userId)
        val pos = if ("bottom" == position) "fondo" else "tope"
        update.logEntry = player.username + " vio el " + pos + " " + n + " del mazo"
        return update
    }

    /** Broadcast-only: the action of viewing the life pile is public, hidden cards stay hidden. */
    @JvmStatic
    fun handleViewLifeStack(room: GameRoom, userId: String?): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        val update = GameStateUpdate.from(room, ActionType.VIEW_LIFE_STACK, userId)
        update.logEntry = player.username + " vio su pila de vida"
        return update
    }

    /** Broadcast-only: the peek action is public, the specific card peeked is not. */
    @JvmStatic
    fun handleViewLifeCard(room: GameRoom, userId: String?): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        val update = GameStateUpdate.from(room, ActionType.VIEW_LIFE_CARD, userId)
        update.logEntry = player.username + " vio una carta oculta de su vida"
        return update
    }

    /**
     * Bulk-sends a selection of cards from any zone to the bottom of the deck in
     * ONE atomic server-side operation — replaces the previous approach of firing
     * one MOVE_CARD per selected card back-to-back from the client (which raced
     * client-side optimistic mutation against async per-card server responses).
     * `instanceIds` must already be in the desired base order (the zone-list
     * order); this handler only decides whether to additionally shuffle it.
     */
    @JvmStatic
    fun handleBulkToDeckBottom(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        val zoneName = action.getString("zone")
        val zone = getZone(player, zoneName) ?: return GameStateUpdate.error(room.roomId, SimMessages.INVALID_ZONE)

        @Suppress("UNCHECKED_CAST")
        val instanceIds = action.payload!!["instanceIds"] as List<String?>?
        val random = action.getBoolean("random", false)
        if (instanceIds == null || instanceIds.isEmpty())
            return GameStateUpdate.error(room.roomId, SimMessages.NO_CARDS)

        val byId = LinkedHashMap<String?, CardSlot>()
        for (id in instanceIds) {
            zone.firstOrNull { c -> id!! == c.instanceId }?.let { c -> byId[id] = c }
        }
        if (byId.isEmpty()) return GameStateUpdate.error(room.roomId, SimMessages.CARDS_NOT_FOUND)
        zone.removeAll(byId.values)

        val ordered: MutableList<CardSlot> = ArrayList(byId.values)
        if (random) GameRandom.shuffle(ordered, room.random)
        ordered.forEach { c -> c.faceDown = true; c.tapped = false; c.x = null; c.y = null }
        player.deck.addAll(ordered)

        val update = GameStateUpdate.from(room, ActionType.BULK_TO_DECK_BOTTOM, userId)
        update.logEntry = (player.username + " envió " + ordered.size + " carta(s) desde " + zoneLabel(zoneName)
                + " al fondo del mazo en orden " + (if (random) "aleatorio" else "de la lista"))
        return update
    }

    /**
     * Moves a selection of cards from one zone to another in one action (instead of one MOVE_CARD per card),
     * each as MOVE_CARD would; one line of history for all of them.
     */
    @JvmStatic
    fun handleBulkMove(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val instanceIds = action.getStringList("instanceIds")
        if (instanceIds.isEmpty()) return GameStateUpdate.error(room.roomId, SimMessages.NO_CARDS)
        var moved = 0
        for (id in instanceIds) {
            val payload: MutableMap<String, Any?> = HashMap(action.payload!!)
            payload.remove("instanceIds")
            payload["instanceId"] = id
            val one = GameAction()
            one.type = "MOVE_CARD"
            one.payload = payload
            if (handleMove(room, userId, one).error == null) moved++
        }
        if (moved == 0) return GameStateUpdate.error(room.roomId, SimMessages.CARDS_NOT_FOUND)
        val tp = action.getString("targetPlayerId")
        val player = room.getPlayer(tp ?: userId)
        val update = GameStateUpdate.from(room, ActionType.BULK_MOVE, userId)
        update.logEntry = (player!!.username + " envió " + moved + " carta(s) desde " + zoneLabel(action.getString("fromZone"))
                + " a " + zoneLabel(action.getString("toZone")))
        return update
    }

    @JvmStatic
    fun handleLifeHeal(room: GameRoom, userId: String?): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        if (player.deck.isEmpty()) return GameStateUpdate.error(room.roomId, SimMessages.DECK_EMPTY)
        val card = player.deck.removeAt(0)
        card.faceDown = true
        player.lifeStack.add(0, card)
        val update = GameStateUpdate.from(room, ActionType.LIFE_HEAL, userId)
        update.logEntry = player.username + " curó vida (tope del mazo)"
        return update
    }

    @JvmStatic
    fun handleLifeShuffle(room: GameRoom, userId: String?): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        GameRandom.shuffle(player.lifeStack, room.random)
        val update = GameStateUpdate.from(room, ActionType.LIFE_SHUFFLE, userId)
        update.logEntry = player.username + " barajó su vida"
        return update
    }

    @JvmStatic
    fun handleLifeAllDown(room: GameRoom, userId: String?): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        player.lifeStack.forEach { c -> c.faceDown = true }
        val update = GameStateUpdate.from(room, ActionType.LIFE_ALL_DOWN, userId)
        update.logEntry = player.username + " ocultó toda su vida"
        return update
    }

    @JvmStatic
    fun handleLifeAllUp(room: GameRoom, userId: String?): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        player.lifeStack.forEach { c -> c.faceDown = false }
        val update = GameStateUpdate.from(room, ActionType.LIFE_ALL_UP, userId)
        update.logEntry = player.username + " reveló toda su vida"
        return update
    }

    @JvmStatic
    fun handleLifeRestart(room: GameRoom, userId: String?): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        if (!player.lifeStack.isEmpty()) return GameStateUpdate.error(room.roomId, SimMessages.LIFE_STACK_NOT_EMPTY)
        val count = min(6, player.deck.size)
        for (i in 0 until count) {
            val card = player.deck.removeAt(0)
            card.faceDown = true
            player.lifeStack.add(0, card)
        }
        val update = GameStateUpdate.from(room, ActionType.LIFE_RESTART, userId)
        update.logEntry = player.username + " reinició su vida (+" + count + " del mazo)"
        return update
    }

    @JvmStatic
    fun handleDeckReorderTop(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)

        @Suppress("UNCHECKED_CAST")
        val instanceIds = action.payload!!["instanceIds"] as List<String?>?
        val position = action.getString("position")
        val shuffle = action.payload!!["shuffle"] == true

        if (instanceIds == null || instanceIds.isEmpty())
            return GameStateUpdate.error(room.roomId, SimMessages.NO_CARDS)

        val byId = LinkedHashMap<String?, CardSlot>()
        for (id in instanceIds) {
            player.deck
                    .firstOrNull { c -> id!! == c.instanceId }
                    ?.let { c -> byId[id] = c }
        }
        player.deck.removeAll(byId.values)

        val ordered: MutableList<CardSlot> = ArrayList(byId.values)
        if (shuffle) GameRandom.shuffle(ordered, room.random)

        if ("bottom" == position) {
            player.deck.addAll(ordered)
        } else {
            for (i in ordered.size - 1 downTo 0) {
                player.deck.add(0, ordered[i])
            }
        }
        val update = GameStateUpdate.from(room, ActionType.DECK_REORDER_TOP, userId)
        val pos = if ("bottom" == position) "fondo" else "tope"
        update.logEntry = player.username + " devolvió " + ordered.size + " carta(s) al " + pos + " del mazo"
        return update
    }

    @JvmStatic
    fun handleZoneReorder(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)

        val zoneName = action.getString("zone")
        @Suppress("UNCHECKED_CAST")
        val instanceIds = action.payload!!["instanceIds"] as List<String?>?

        val zone = getZone(player, zoneName) ?: return GameStateUpdate.error(room.roomId, SimMessages.INVALID_ZONE)
        if (instanceIds == null) return GameStateUpdate.error(room.roomId, SimMessages.NO_ORDER)

        val cardMap = LinkedHashMap<String?, CardSlot>()
        for (c in zone) cardMap[c.instanceId] = c

        val reordered = instanceIds.mapNotNull { cardMap[it] }
        zone.clear()
        zone.addAll(reordered)

        return GameStateUpdate.from(room, ActionType.ZONE_REORDER, userId)
    }

    @JvmStatic
    fun handleDeckIncludeDiscard(room: GameRoom, userId: String?): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        val discard: MutableList<CardSlot> = ArrayList(player.discardPile)
        val names = discard.joinToString(", ") { c -> if (c.faceDown) "una carta oculta" else cardLabel(c) }
        GameRandom.shuffle(discard, room.random)
        discard.forEach { c -> c.faceDown = true }
        player.discardPile.clear()
        player.deck.addAll(discard)
        val update = GameStateUpdate.from(room, ActionType.DECK_INCLUDE_DISCARD, userId)
        update.logEntry = (player.username + " mezcló el descarte con el mazo"
                + (if (names.isEmpty()) "" else ": " + names))
        return update
    }

    @JvmStatic
    fun handleDeckIncludeTribute(room: GameRoom, userId: String?): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        val tribute: MutableList<CardSlot> = ArrayList(player.tributeZone)
        val names = tribute.joinToString(", ") { c -> if (c.faceDown) "una carta oculta" else cardLabel(c) }
        GameRandom.shuffle(tribute, room.random)
        tribute.forEach { c -> c.faceDown = true }
        player.tributeZone.clear()
        player.deck.addAll(tribute)
        val update = GameStateUpdate.from(room, ActionType.DECK_INCLUDE_TRIBUTE, userId)
        update.logEntry = (player.username + " mezcló el tributo con el mazo"
                + (if (names.isEmpty()) "" else ": " + names))
        return update
    }

    @JvmStatic
    fun handleRevealToHand(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        val instanceId = action.getString("instanceId")
        val card = player.deck
                .firstOrNull { c -> instanceId!! == c.instanceId }
                ?: return GameStateUpdate.error(room.roomId, SimMessages.CARD_NOT_IN_DECK)
        player.deck.remove(card)
        card.faceDown = false
        player.hand.add(card)
        val update = GameStateUpdate.from(room, ActionType.REVEAL_TO_HAND, userId)
        update.revealedCardImageUrl = card.imageUrl
        update.revealedCardName = card.cardName
        update.logEntry = player.username + " reveló " + cardLabel(card) + " y la añadió a la mano"
        return update
    }

    @JvmStatic
    fun getZone(player: PlayerState, zoneName: String?): MutableList<CardSlot>? {
        return Zone.parse(zoneName)?.let { z -> z.of(player) }
    }
}
