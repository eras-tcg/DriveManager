package com.drive.drive_manager.simulator.room

import kotlin.jvm.JvmStatic
import com.drive.drive_manager.simulator.bot.BotSeat

/**
 * Who may do what in a room, checked before any action (what each player is sent is StateMask's): only the room's
 * players act on it, and an online game is played by the rules, so the board's manual actions are for test rooms
 * and the site's editors.
 */
object RoomGuard {

    /** By hand a creature's strength changes a few points at a time (the board sends ±1 or undoes what it changed). */
    const val MAX_STRENGTH_CHANGE = 50

    /** Why the action is refused before it is applied, or null. `manual`: the player may use the manual actions. */
    @JvmStatic
    fun refusal(room: GameRoom, userId: String?, action: GameAction, manual: Boolean): String? {
        val join = ActionType.JOIN_ROOM.`is`(action)
        if (!join && !room.players.containsKey(userId)) return SimMessages.NOT_IN_ROOM
        if (join && !room.reserved.isEmpty() && !room.reserved.contains(userId))
            return "Esta sala es de una partida de la cola ranked"
        if (!room.editor && !manual && !ActionType.has(action, ActionType.Flag.RULES))
            return "Esa acción manual solo está disponible en partidas de prueba"
        return null
    }

    /** A game in course (from the starters' choice to its end). */
    @JvmStatic
    fun playing(room: GameRoom): Boolean {
        return room.status.isPlaying()
    }

    /** A game between two players is going on: from choosing the starters to its end. */
    @JvmStatic
    fun inGame(room: GameRoom): Boolean {
        return room.players.size == 2 && playing(room)
    }

    /**
     * A deck the player may use: their own or a public one (the rival's side of a test room, and the bot, use its owner's).
     * `privateDeck` and `deckOwner`: the deck's (the server's DeckList), so the core needs none of the server's classes.
     */
    @JvmStatic
    fun usableDeck(privateDeck: Boolean, deckOwner: String?, room: GameRoom, userId: String?): Boolean {
        val owner = if (RoomEngine.EDITOR_RIVAL == userId || BotSeat.BOT == userId) room.hostId else userId
        return !privateDeck || owner != null && owner == deckOwner
    }
}
