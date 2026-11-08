package com.drive.drive_manager.simulator.room

import com.drive.drive_manager.simulator.actions.PreRoomActions
import com.drive.drive_manager.simulator.bot.BotSeat
import com.drive.drive_manager.simulator.bot.RoomBots
import kotlin.jvm.JvmStatic

/**
 * The pre-room's decks as the rules see them (the room session's deck choice, [RoomHost.setDeck] and
 * [RoomHost.refreshCardMetadata]): a deck found by whoever keeps them made the player's, the bot's copy of it, a card's
 * metadata read again. One copy for the server (`RoomDecks`: the decks in Mongo, the player's collection and finishes)
 * and for a game in the browser (`LocalRoom`: the match's decks); what only they know is asked of their [Shelf].
 */
object DeckChoice {

    /** A deck found by its id: what the rules check of it, and its cards as a room plays them (read only when it may be used). */
    class Found(val name: String?, val privateDeck: Boolean, val ownerId: String?, private val cards: () -> List<CardSlot>) {
        fun slots(): List<CardSlot> = cards()
    }

    /** Where the decks are (the server's Mongo, the match's decks). */
    interface Shelf {
        /** The deck of that id, or null. */
        fun find(deckId: String): Found?

        /** The id of a new card instance (the server's are random UUIDs). */
        fun nextInstanceId(): String

        /** Why the player may not play these cards (the server: not all in their collection), or null. */
        fun refusal(userId: String?, slots: List<CardSlot>): String? = null

        /** The player's own finishes on their cards (the server's). */
        fun withFinishes(userId: String?, slots: List<CardSlot>) {}

        /** Every copy of the card in the room takes its metadata again; false when there is none. */
        fun refresh(room: GameRoom, cardId: String): Boolean
    }

    /** Pre-room: the deck a player will use; its first Iniciador is the starter until another is chosen. */
    @JvmStatic
    fun setDeck(room: GameRoom, userId: String?, action: GameAction, shelf: Shelf): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        if (room.status != GameRoom.RoomStatus.WAITING) return GameStateUpdate.error(room.roomId, SimMessages.GAME_STARTED)
        val deckId = action.getString("deckId") ?: return GameStateUpdate.error(room.roomId, "Falta el mazo")
        val deck = shelf.find(deckId)
        if (deck == null || !RoomGuard.usableDeck(deck.privateDeck, deck.ownerId, room, userId))
            return GameStateUpdate.error(room.roomId, SimMessages.DECK_NOT_FOUND)
        val slots = deck.slots()
        if (slots.isEmpty()) return GameStateUpdate.error(room.roomId, SimMessages.DECK_NOT_FOUND)
        // The bot plays the same deck as its own cards (new instances, without the owner's finishes)
        val forBot = if (room.bot) freshCopy(slots) { shelf.nextInstanceId() } else null
        // Cards the engine does not run cannot be played; a test room plays them by hand (it is where they are tried)
        if (!room.editor && unprogrammedIn(slots) != null) return GameStateUpdate.error(room.roomId,
            "Este mazo tiene cartas que todavía no están programadas en el simulador")
        // Only with every copy in the player's collection (a test room plays anything: it is where cards are tried; the bot owns none)
        val isBot = BotSeat.BOT == userId
        if (!room.editor && !isBot) shelf.refusal(userId, slots)?.let { return GameStateUpdate.error(room.roomId, it) }
        if (!isBot) shelf.withFinishes(userId, slots)
        player.deckId = deckId
        player.deckName = deck.name
        player.deck = ArrayList(slots)
        player.originalDeck = PreRoomActions.deepCopySlots(slots)
        player.starterCardId = slots.firstOrNull { it.starter }?.cardId
        player.ready = false
        if (room.bot && !isBot) RoomBots.mirrorDeck(room, player, forBot!!)
        return GameStateUpdate.from(room, ActionType.SET_DECK, userId)
    }

    /** A card's metadata read again (edited on the site meanwhile), into every copy in the room. */
    @JvmStatic
    fun refreshCardMetadata(room: GameRoom, userId: String?, action: GameAction, shelf: Shelf): GameStateUpdate {
        val cardId = action.getString("cardId") ?: return GameStateUpdate.error(room.roomId, SimMessages.NO_CARD)
        if (!shelf.refresh(room, cardId)) return GameStateUpdate.error(room.roomId, SimMessages.METADATA_NOT_FOUND)
        return GameStateUpdate.from(room, ActionType.REFRESH_CARD_METADATA, userId)
    }

    /** The same cards as new instances (another seat playing the same deck: the bot's copy of its owner's). */
    @JvmStatic
    fun freshCopy(slots: List<CardSlot>, nextId: () -> String): MutableList<CardSlot> {
        val copy = PreRoomActions.deepCopySlots(slots)
        copy.forEach { it.instanceId = nextId() }
        return copy
    }

    /** The names of a deck's cards the engine does not run in full ("×2 Roto-G0, Sicario R3-D"), or null. */
    @JvmStatic
    fun unprogrammedIn(slots: List<CardSlot>): String? {
        val names = LinkedHashMap<String, Int>()
        for (s in slots) if (s.unprogrammed != null) {
            val name = s.cardName ?: "?"
            names[name] = (names[name] ?: 0) + 1
        }
        if (names.isEmpty()) return null
        return names.entries.joinToString(", ") { (name, n) -> if (n > 1) "×$n $name" else name }
    }

    /** Every copy of the card in the room, wherever it is (attached ones too), filled again by `apply`. */
    @JvmStatic
    fun refresh(room: GameRoom, cardId: String, apply: (CardSlot) -> Unit) {
        for (player in room.players.values) {
            for (zone in listOf(player.hand, player.deck, player.originalDeck, player.lifeStack, player.tributeZone, player.discardPile)) {
                zone.filter { cardId == it.cardId }.forEach(apply)
            }
            for (fs in player.field) {
                if (cardId == fs.cardId) apply(fs)
                fs.materials!!.filter { cardId == it.cardId }.forEach(apply)
                fs.resources!!.filter { cardId == it.cardId }.forEach(apply)
            }
        }
    }
}
