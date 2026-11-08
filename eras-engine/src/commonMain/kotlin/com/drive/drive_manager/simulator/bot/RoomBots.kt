package com.drive.drive_manager.simulator.bot

import com.drive.drive_manager.simulator.actions.PreRoomActions
import com.drive.drive_manager.simulator.engine.frozenMapOf
import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.DeckChoice
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.GameStateUpdate
import com.drive.drive_manager.simulator.room.PlayerState
import com.drive.drive_manager.simulator.room.RoomEngine
import kotlin.jvm.JvmStatic

/**
 * The bots of a room as the rules see them (the room session's bots): the bot's seat and deck, the owner's settings
 * (level, ranked, the test room's bots, pace, edge) and what one move of a bot is. One copy for the server (`BotRooms`
 * adds its threads, pauses and copies to think on) and for a game in the browser (`LocalRoom`); the bot players
 * themselves are kept by whoever runs them, told through their [Roster].
 */
object RoomBots {

    /** Who keeps the room's bot players (the server's, by room; a LocalMatch's one bot). */
    interface Roster {
        /** The bot of the room's bot seat is now a new one at this level or model. */
        fun newBot(room: GameRoom, level: String?)

        /** The owner's strength in the ranked ladder (a new player's, [com.drive.drive_manager.simulator.rank.RankRules.START_MMR], without one). */
        fun ownerStrength(room: GameRoom): Int

        /** The id of a new card instance (the bot's copy of its owner's deck). */
        fun nextInstanceId(): String

        /** Sala de pruebas: the bot of the rival's side is switched off. */
        fun dropBot(room: GameRoom) {}

        /** Sala de pruebas: a new bot at this level plays the owner's own side. */
        fun newSelfBot(room: GameRoom, level: String?) {}

        /** Sala de pruebas: no bot plays the owner's side any more. */
        fun dropSelfBot(room: GameRoom) {}
    }

    /** Seats the bot in a new room against it. */
    @JvmStatic
    fun seat(room: GameRoom, roster: Roster) {
        room.bot = true
        val seat = PlayerState(BotSeat.BOT, BotCatalog.nameOf(room.botLevel))
        seat.connected = true
        room.players[BotSeat.BOT] = seat
        roster.newBot(room, room.botLevel)
        if (room.ranked) rankedBot(room, roster)
    }

    /** Every setting of the room's bots ([com.drive.drive_manager.simulator.room.RoomHost.botSetting]). */
    @JvmStatic
    fun setting(type: ActionType, room: GameRoom, userId: String?, action: GameAction, roster: Roster): GameStateUpdate = when (type) {
        ActionType.SET_BOT_LEVEL -> setLevel(room, userId, action, roster)
        ActionType.SET_EDITOR_BOT -> setEditorBot(room, userId, action, roster)
        ActionType.SET_EDITOR_SELF_BOT -> setEditorSelfBot(room, userId, action, roster)
        ActionType.SET_BOT_DELAY -> setDelay(room, userId, action)
        ActionType.SET_BOT_EDGE -> setEdge(room, userId, action)
        ActionType.SET_RANKED -> setRanked(room, userId, action, roster)
        else -> throw IllegalArgumentException("Not a bot setting: $type")
    }

    /** The owner chooses the bot's level before the game (in the Sala de pruebas, at any moment) ("level": easy, normal or hard); a new bot takes the seat. */
    @JvmStatic
    fun setLevel(room: GameRoom, userId: String?, action: GameAction, roster: Roster): GameStateUpdate {
        val level = action.getString("level")
        if ("self" == action.getString("side")) return setSelfLevel(room, userId, level, roster)
        if (!room.bot && !room.editorBot) return GameStateUpdate.error(room.roomId, "Esta sala no es contra el bot")
        if (userId!! != room.hostId) return GameStateUpdate.error(room.roomId, "Solo el dueño de la sala elige el nivel del bot")
        if (room.ranked) return GameStateUpdate.error(room.roomId, "En una partida ranked el nivel del bot lo da tu rango")
        if (!room.editor && room.status != GameRoom.RoomStatus.WAITING) return GameStateUpdate.error(room.roomId, "El nivel se elige antes de empezar la partida")
        if (level == null || !BotCatalog.LEVELS.contains(level)) return GameStateUpdate.error(room.roomId, "Ese nivel no existe")
        room.botLevel = level
        roster.newBot(room, level)
        if (room.bot) room.getPlayer(BotSeat.BOT)?.username = BotCatalog.nameOf(level)
        return GameStateUpdate.from(room, ActionType.SET_BOT_LEVEL, userId)
    }

    /** Sala de pruebas: the level or model of the bot that plays the owner's side (kept for when it is switched on). */
    private fun setSelfLevel(room: GameRoom, userId: String?, level: String?, roster: Roster): GameStateUpdate {
        if (!room.editor) return GameStateUpdate.error(room.roomId, "Solo en la sala de pruebas un bot juega tu lado")
        if (userId!! != room.hostId) return GameStateUpdate.error(room.roomId, "Solo el dueño de la sala elige el nivel del bot")
        if (level == null || !BotCatalog.LEVELS.contains(level)) return GameStateUpdate.error(room.roomId, "Ese nivel no existe")
        room.selfBotLevel = level
        if (room.editorSelfBot) roster.newSelfBot(room, level)
        return GameStateUpdate.from(room, ActionType.SET_BOT_LEVEL, userId)
    }

    /**
     * The owner makes the game ranked ("ranked": true) or friendly, before it starts (not in a test room). Ranked: who
     * goes first is drawn and, against the bot, it plays the owner's deck at the level of their strength (MMR).
     */
    @JvmStatic
    fun setRanked(room: GameRoom, userId: String?, action: GameAction, roster: Roster): GameStateUpdate {
        if (room.editor) return GameStateUpdate.error(room.roomId, "La sala de pruebas no es ranked")
        if (userId!! != room.hostId) return GameStateUpdate.error(room.roomId, "Solo el dueño de la sala elige el tipo de partida")
        if (room.status != GameRoom.RoomStatus.WAITING) return GameStateUpdate.error(room.roomId, "El tipo de partida se elige antes de empezar")
        if (!room.reserved.isEmpty()) return GameStateUpdate.error(room.roomId, "Una partida de la cola ranked siempre es ranked")
        val ranked = action.getBoolean("ranked", false)
        room.ranked = ranked
        room.players.values.forEach { p -> if (BotSeat.BOT != p.userId) p.ready = false }
        if (ranked) {
            room.firstChoice = "random"
            if (room.bot) rankedBot(room, roster)
        } else if (room.bot) {
            room.botEdge = 0
        }
        return GameStateUpdate.from(room, ActionType.SET_RANKED, userId)
    }

    /** Ranked against the bot: it plays at the level of the owner's strength (MMR) with a copy of their deck. */
    private fun rankedBot(room: GameRoom, roster: Roster) {
        val strength = roster.ownerStrength(room)
        val level = BotCatalog.levelFor(strength)
        room.botLevel = level
        room.botEdge = if (BotCatalog.HARD == level) BotEdge.levelFor(strength) else 0
        roster.newBot(room, level)
        val bot = room.getPlayer(BotSeat.BOT) ?: return
        val owner = room.getPlayer(room.hostId)
        bot.username = BotCatalog.nameOf(level)
        bot.deckId = null
        bot.deckName = null
        bot.deck = ArrayList()
        bot.originalDeck = ArrayList()
        bot.starterCardId = null
        // Its own instances of the owner's cards (sharing their ids, the board and the engine would mix the two sides up),
        // without the owner's finishes
        if (owner?.deckId != null) {
            val copy = DeckChoice.freshCopy(owner.originalDeck) { roster.nextInstanceId() }
            copy.forEach { it.finish = null }
            mirrorDeck(room, owner, copy)
        }
    }

    /** The bot plays the owner's deck until they choose another one for it (ranked: always the owner's deck). */
    @JvmStatic
    fun mirrorDeck(room: GameRoom, owner: PlayerState, deck: List<CardSlot>) {
        val bot = if (room.bot) room.getPlayer(BotSeat.BOT) else null
        if (bot == null || bot.deckId != null && !room.ranked || owner.userId == BotSeat.BOT) return
        bot.deckId = owner.deckId
        bot.deckName = owner.deckName
        bot.deck = ArrayList(deck)
        bot.originalDeck = PreRoomActions.deepCopySlots(deck)
        bot.starterCardId = deck.firstOrNull { it.starter }?.cardId
    }

    /**
     * Sala de pruebas: the owner has the bot play the test rival's side ("on": true) or plays it again by hand (false), at
     * any moment; the bot plays at the room's level or model, and everything stays in view as in any test room.
     */
    @JvmStatic
    fun setEditorBot(room: GameRoom, userId: String?, action: GameAction, roster: Roster): GameStateUpdate {
        if (!room.editor) return GameStateUpdate.error(room.roomId, "Solo en la sala de pruebas el bot juega el lado del rival")
        if (userId!! != room.hostId) return GameStateUpdate.error(room.roomId, "Solo el dueño de la sala decide quién juega el rival")
        val on = true == action.payload?.get("on")
        room.editorBot = on
        if (on) roster.newBot(room, room.botLevel) else roster.dropBot(room)
        return GameStateUpdate.from(room, ActionType.SET_EDITOR_BOT, userId)
    }

    /**
     * Sala de pruebas: a bot plays the owner's own side too ("on": true), at the level or model "selfBotLevel", so the owner
     * watches two bots play; off, they play it by hand again. At any moment.
     */
    @JvmStatic
    fun setEditorSelfBot(room: GameRoom, userId: String?, action: GameAction, roster: Roster): GameStateUpdate {
        if (!room.editor) return GameStateUpdate.error(room.roomId, "Solo en la sala de pruebas un bot juega tu lado")
        if (userId!! != room.hostId) return GameStateUpdate.error(room.roomId, "Solo el dueño de la sala decide quién juega su lado")
        val on = true == action.payload?.get("on")
        if (on && !room.adminPlayers.contains(userId))
            return GameStateUpdate.error(room.roomId, "Solo un administrador del simulador puede hacer que un bot juegue su lado")
        room.editorSelfBot = on
        if (on) roster.newSelfBot(room, room.selfBotLevel) else roster.dropSelfBot(room)
        return GameStateUpdate.from(room, ActionType.SET_EDITOR_SELF_BOT, userId)
    }

    /** The pause before each move of the room's bots, in ms ("ms"; null or below 0: each bot's own); at any moment. */
    @JvmStatic
    fun setDelay(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val refused = pace(room, userId, action)
        return if (refused != null) GameStateUpdate.error(room.roomId, refused) else GameStateUpdate.from(room, ActionType.SET_BOT_DELAY, userId)
    }

    /**
     * Sets the pace, with no need of the room's lock (a bot may be thinking with it held): it changes no game state,
     * only how long the next pause is. The refusal, or null.
     */
    @JvmStatic
    fun pace(room: GameRoom, userId: String?, action: GameAction): String? {
        if (!room.editor && !room.bot) return "Esta sala no tiene bots"
        if (userId!! != room.hostId) return "Solo el dueño de la sala elige el ritmo de los bots"
        val ms = action.payload?.get("ms")
        room.botDelayMs = if (ms is Number && ms.toInt() >= 0) minOf(10_000, ms.toInt()) else null
        return null
    }

    /**
     * A `SET_BOT_DELAY` applied at once, before the room's turn of actions (the board shows it, the next update has it):
     * nothing to send when it is accepted (null), else its error for who sent it.
     */
    @JvmStatic
    fun paceAtOnce(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate? {
        val refused = pace(room, userId, action) ?: return null
        val update = GameStateUpdate.error(room.roomId, refused)
        update.actorId = userId
        return update
    }

    /** A test room's owner gives its rival's side an edge, 0–8 (BotEdge); it applies from the next turn on. */
    @JvmStatic
    fun setEdge(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        if (!room.editor) return GameStateUpdate.error(room.roomId, "La ventaja del bot se elige solo en la sala de pruebas")
        if (userId!! != room.hostId) return GameStateUpdate.error(room.roomId, "Solo el dueño de la sala elige la ventaja del bot")
        val level = action.payload?.get("level")
        room.botEdge = BotEdge.clamp(if (level is Number) level.toInt() else 0)
        return GameStateUpdate.from(room, ActionType.SET_BOT_EDGE, userId)
    }

    // ------------------------------------------------------------------ A move of a bot

    /** What [move] did. */
    enum class Move { NOTHING, MOVED, CONCEDED }

    /** The bots may play in the room now: not paused, and in a test room only the game itself (not the pre-room nor its end). */
    @JvmStatic
    fun mayMove(room: GameRoom): Boolean {
        if (room.paused) return false
        return !room.editor || room.status != GameRoom.RoomStatus.WAITING && room.status != GameRoom.RoomStatus.POST_GAME
    }

    /**
     * One move of `bot` for `seat`: `play` applies an action of that seat to the room (its error tagged), `send` sends
     * what it changed. A move the engine refuses is told to the bot, which then tries another one; a bot that runs out
     * of moves ([BotPlayer.MAX_ATTEMPTS]) concedes, so a game never waits for it forever.
     */
    @JvmStatic
    fun move(room: GameRoom, bot: BotPlayer, seat: String, play: (GameAction) -> GameStateUpdate?, send: (GameStateUpdate?) -> Unit): Move {
        // Sala de pruebas: the owner confirms for both sides and chooses after the game, so the bot only plays the game
        if (!mayMove(room)) return Move.NOTHING
        for (attempt in 0 until BotPlayer.MAX_ATTEMPTS) {
            val action = bot.next(room, seat) ?: return Move.NOTHING
            val update = play(action)
            if (update == null || update.error == null) {
                bot.played(room, action)
                send(update)
                return Move.MOVED
            }
            bot.refused(action, update.error)
        }
        concede(room, play, send)
        return Move.CONCEDED
    }

    /** A bot out of valid moves concedes (a game never waits for it forever); its update is sent when it is accepted. */
    @JvmStatic
    fun concede(room: GameRoom, play: (GameAction) -> GameStateUpdate?, send: (GameStateUpdate?) -> Unit) {
        val update = play(BotPlayer.action(ActionType.SURRENDER, frozenMapOf()))
        if (update != null && update.error == null) {
            update.logEntry = "El bot no encontró una jugada válida y se rindió"
            RoomEngine.recordLog(room, update, null)
            update.eventLog = ArrayList(room.eventLog)
            send(update)
        }
    }
}
