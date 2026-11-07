package com.drive.drive_manager.simulator.actions

import com.drive.drive_manager.simulator.actions.TurnActions.getPlayerDirection
import com.drive.drive_manager.simulator.bot.BotEdge
import com.drive.drive_manager.simulator.engine.GameRandom
import com.drive.drive_manager.simulator.replay.MatchSetup
import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.GameStateUpdate
import com.drive.drive_manager.simulator.room.SimMessages

/** The room before and after a game: decks, starter, mulligan. */
object PreRoomActions {

    /** Rule 3.0.1: who starts; the other player gets the "ventaja del iniciador" (rule 6.2.1.4). */
    @JvmStatic
    fun setFirstPlayer(room: GameRoom, firstPlayerId: String?) {
        room.currentTurnUserId = firstPlayerId
        room.firstPlayerId = firstPlayerId
        room.firstTurn = true
        room.starterAdvantageUserId = room.getOpponentId(firstPlayerId)
    }

    @JvmStatic
    fun handleSetStarter(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        if (room.status != GameRoom.RoomStatus.WAITING) return GameStateUpdate.error(room.roomId, SimMessages.GAME_STARTED)
        val cardId = action.getString("cardId")
        if (player.deck.none { c -> c.starter && c.cardId!! == cardId })
            return GameStateUpdate.error(room.roomId, "Esa carta no es un Iniciador de tu mazo")
        player.starterCardId = cardId
        player.ready = false
        return GameStateUpdate.from(room, ActionType.SET_STARTER, userId)
    }

    /** The host chooses who takes the first turn: a player or "random". Both confirm again. */
    @JvmStatic
    fun handleSetFirstPlayer(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        if (userId!! != room.hostId) return GameStateUpdate.error(room.roomId, "Solo el dueño de la sala elige quién empieza")
        if (room.ranked) return GameStateUpdate.error(room.roomId, "En una partida ranked quién empieza se sortea")
        if (room.status != GameRoom.RoomStatus.WAITING) return GameStateUpdate.error(room.roomId, SimMessages.GAME_STARTED)
        val choice = action.getString("choice")
        if (choice == null || ("random" != choice && !room.players.containsKey(choice)))
            return GameStateUpdate.error(room.roomId, SimMessages.PLAYER_NOT_FOUND)
        room.firstChoice = choice
        room.players.values.forEach { p -> p.ready = false }
        return GameStateUpdate.from(room, ActionType.SET_FIRST_PLAYER, userId)
    }

    /** A player confirms (or not) that they start; with both ready the game starts at the mulligan. */
    @JvmStatic
    fun handleSetReady(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        if (room.status != GameRoom.RoomStatus.WAITING) return GameStateUpdate.error(room.roomId, SimMessages.GAME_STARTED)
        val ready = action.getBoolean("ready", true)
        if (ready && (player.deckId == null || player.starterCardId == null))
            return GameStateUpdate.error(room.roomId, "Elige un mazo con un Iniciador")
        if (room.editor && ready) {
            // The owner confirms for both sides
            val other = room.getPlayer(room.getOpponentId(userId))
            if (other == null || other.deckId == null || other.starterCardId == null)
                return GameStateUpdate.error(room.roomId, "Elige también el mazo del otro lado")
            other.ready = true
        }
        player.ready = ready
        val all = room.players.size == 2 && room.players.values.all { it.ready }
        if (all) startFromPreRoom(room)
        return GameStateUpdate.from(room, if (all) "START_GAME" else "SET_READY", userId)
    }

    /**
     * Starts the game from the pre-room choices: who goes first (rule 3.0.1), each starter set aside and public
     * (rule 3.0.2), decks shuffled; then the mulligan.
     */
    @JvmStatic
    fun startFromPreRoom(room: GameRoom) {
        startFromPreRoom(room, room.random!!.nextLong())
    }

    /**
     * The same, the game's chance starting from `seed`: how it starts is kept (MatchSetup) so the game can be
     * played again exactly.
     */
    @JvmStatic
    fun startFromPreRoom(room: GameRoom, seed: Long) {
        room.startSetup = MatchSetup.of(room, seed)
        room.random = java.util.Random(seed)
        var first = room.firstChoice
        if (first == null || "random" == first || !room.players.containsKey(first)) {
            val ids: List<String> = ArrayList(room.players.keys)
            first = ids[room.random!!.nextInt(ids.size)]
        }
        setFirstPlayer(room, first)
        room.currentPhase = "MAIN"
        room.starterSelections.clear()
        room.mulliganReady.clear()
        for (player in room.players.values) {
            player.ready = false
            val starter = player.deck.firstOrNull { c -> c.starter && c.cardId!! == player.starterCardId }
                    ?: player.deck.firstOrNull { it.starter }
                    ?: continue
            player.deck.remove(starter)
            starter.faceDown = false
            starter.x = 50.0
            starter.y = 75.0
            player.field.add(starter)
            starterSelections(room)[player.userId] = starter.instanceId
        }
        room.players.values.forEach { p -> GameRandom.shuffle(p.deck, room.random) }
        room.status = GameRoom.RoomStatus.MULLIGAN
    }

    /** The room's starter selections, as Java filled them: by possibly null ids. */
    @Suppress("UNCHECKED_CAST")
    private fun starterSelections(room: GameRoom): MutableMap<String?, String?> =
        room.starterSelections as MutableMap<String?, String?>

    @JvmStatic
    fun handleSelectStarter(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        if (room.status != GameRoom.RoomStatus.SELECTING_STARTER)
            return GameStateUpdate.error(room.roomId, "No estás en selección de starter")
        if (starterSelections(room).containsKey(userId))
            return GameStateUpdate.error(room.roomId, "Ya seleccionaste tu starter")
        val instanceId = action.getString("instanceId")
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        val starter = player.deck
                .firstOrNull { c -> instanceId!! == c.instanceId }
                ?: return GameStateUpdate.error(room.roomId, SimMessages.CARD_NOT_FOUND)
        if (!starter.starter) return GameStateUpdate.error(room.roomId, "Esa carta no es un starter válido")
        player.deck.remove(starter)
        // Rule 3.0.2: set aside and public until the mulligan is done (kept on the field meanwhile)
        starter.faceDown = false
        starter.x = 50.0
        starter.y = 75.0
        player.field.add(starter)
        starterSelections(room)[userId] = instanceId
        if (room.starterSelections.size >= room.players.size) {
            room.players.values.forEach { p -> GameRandom.shuffle(p.deck, room.random) }
            room.mulliganReady.clear()
            room.status = GameRoom.RoomStatus.MULLIGAN
        }
        return GameStateUpdate.from(room, ActionType.SELECT_STARTER, userId)
    }

    @JvmStatic
    fun handleConfirmMulligan(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        if (room.status != GameRoom.RoomStatus.MULLIGAN)
            return GameStateUpdate.error(room.roomId, "No estás en mulligán")
        if (mulliganReady(room).contains(userId))
            return GameStateUpdate.error(room.roomId, "Ya confirmaste el mulligán")
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        val payload = action.payload
        @Suppress("UNCHECKED_CAST")
        val toBottom: List<String?>? = if (payload != null && payload.containsKey("toBottom"))
            payload["toBottom"] as List<String?>?
        else
            java.util.Collections.emptyList()
        val topN = Math.min(5, player.deck.size)
        val top5: List<CardSlot> = ArrayList(player.deck.subList(0, topN))
        player.deck.subList(0, topN).clear()
        val toBottomCards = top5.filter { c -> toBottom!!.contains(c.instanceId) }
        val keepCards = top5.filter { c -> !toBottom!!.contains(c.instanceId) }
        player.deck.addAll(toBottomCards)
        keepCards.forEach { c -> c.faceDown = false }
        player.hand.addAll(keepCards)
        while (player.hand.size < 5 && !player.deck.isEmpty()) {
            val drawn = player.deck.removeAt(0)
            drawn.faceDown = false
            player.hand.add(drawn)
        }
        GameRandom.shuffle(player.deck, room.random)
        // Rule 3.0.6: after the mulligan the starter joins the hand (6 cards)
        val starterId = starterSelections(room)[userId]
        player.field.firstOrNull { c -> c.instanceId!! == starterId }?.let { starter ->
            player.field.remove(starter)
            starter.x = null
            starter.y = null
            player.hand.add(starter)
        }
        val lifeCount = Math.min(6, player.deck.size)
        for (i in 0 until lifeCount) {
            val c = player.deck.removeAt(0)
            c.faceDown = true
            player.lifeStack.add(c)
        }
        mulliganReady(room).add(userId)
        if (room.mulliganReady.size >= room.players.size) {
            room.status = GameRoom.RoomStatus.IN_PROGRESS
            val firstId = room.currentTurnUserId
            val first = room.getPlayer(firstId)
            if (first != null) {
                first.turnsStarted = 1
                val count = BotEdge.marcador(room, first, 1)
                first.resourceCount = count
                val dir = getPlayerDirection(room, firstId)
                room.sharedCounter = Math.max(-10, Math.min(10, room.sharedCounter + count * dir))
            }
        }
        return GameStateUpdate.from(room, ActionType.CONFIRM_MULLIGAN, userId)
    }

    /** The room's mulligan confirmations, as Java filled them: by a possibly null id. */
    @Suppress("UNCHECKED_CAST")
    private fun mulliganReady(room: GameRoom): MutableSet<String?> = room.mulliganReady as MutableSet<String?>

    @JvmStatic
    fun handleStartGame(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        if (userId!! != room.hostId)
            return GameStateUpdate.error(room.roomId, "Solo el host puede iniciar")
        if (room.status != GameRoom.RoomStatus.WAITING || room.players.size < 2)
            return GameStateUpdate.error(room.roomId, "No se puede iniciar aún")
        var firstPlayerId = action.getString("firstPlayerId")
        if (firstPlayerId == null || "random" == firstPlayerId) {
            val ids: List<String> = ArrayList(room.players.keys)
            firstPlayerId = ids[room.random!!.nextInt(ids.size)]
        }
        if (!room.players.containsKey(firstPlayerId))
            return GameStateUpdate.error(room.roomId, SimMessages.PLAYER_NOT_FOUND)
        setFirstPlayer(room, firstPlayerId)
        room.currentPhase = "MAIN"
        room.starterSelections.clear()
        room.mulliganReady.clear()
        // The older start (starters chosen by hand) is not recorded: it cannot be played again from a MatchSetup
        room.startSetup = null
        room.status = GameRoom.RoomStatus.SELECTING_STARTER
        return GameStateUpdate.from(room, ActionType.START_GAME, userId)
    }

    @JvmStatic
    fun deepCopySlots(slots: List<CardSlot>?): MutableList<CardSlot> {
        return slots!!.mapTo(ArrayList<CardSlot>()) { s ->
            val c = CardSlot()
            c.instanceId = s.instanceId
            c.cardId = s.cardId
            c.imageUrl = s.imageUrl
            c.cardName = s.cardName
            c.cardType = s.cardType
            c.strength = s.strength
            c.specialSummonKind = s.specialSummonKind
            c.starter = s.starter
            c.cost = s.cost
            c.setLevel(s.getPrintedLevel())
            c.continuousLevel = s.continuousLevel
            c.colors = ArrayList(s.colors!!)
            c.classes = ArrayList(s.classes!!)
            c.specialCost = s.specialCost
            c.requirement = s.requirement
            c.keywords = ArrayList(s.keywords!!)
            c.inheritKeywords = ArrayList(s.inheritKeywords!!)
            c.unprogrammed = s.unprogrammed
            c.mentions = ArrayList(s.mentions!!)
            c.finish = s.finish
            c.alt = s.alt
            c.altArtUrls = if (s.altArtUrls == null) java.util.HashMap() else java.util.HashMap(s.altArtUrls!!)
            c.faceDown = true
            c
        }
    }
}
