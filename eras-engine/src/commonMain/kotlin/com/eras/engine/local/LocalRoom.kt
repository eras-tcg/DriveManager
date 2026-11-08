package com.eras.engine.local

import com.drive.drive_manager.simulator.actions.PreRoomActions
import com.drive.drive_manager.simulator.bot.BotCatalog
import com.drive.drive_manager.simulator.bot.BotEdge
import com.drive.drive_manager.simulator.bot.BotPlayer
import com.drive.drive_manager.simulator.bot.BotSeat
import com.drive.drive_manager.simulator.bot.Usefulness
import com.drive.drive_manager.simulator.engine.GameRandom
import com.drive.drive_manager.simulator.engine.frozenMapOf
import com.drive.drive_manager.simulator.platform.Clocks
import com.drive.drive_manager.simulator.platform.Platform
import com.drive.drive_manager.simulator.rank.RankRules
import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.GameStateUpdate
import com.drive.drive_manager.simulator.room.PlayerState
import com.drive.drive_manager.simulator.room.RoomEngine
import com.drive.drive_manager.simulator.room.RoomGuard
import com.drive.drive_manager.simulator.room.RoomHost
import com.drive.drive_manager.simulator.room.SimMessages
import com.drive.drive_manager.simulator.room.StateMask

/**
 * A room against the bot run in the browser exactly as the server runs one (`RoomService`, `RoomMembership`,
 * `RoomDecks`, `BotRooms`, `RoomMessages`), for its one human player. Each part is the server's, ported line by line
 * where the server's is Java (the order of the checks and their messages are the server's):
 * - [send]: `RoomService.applyAndBroadcast` for the player's action (the bots' pace apart, the guards, the dispatch with
 *   this room as the [RoomHost], the error tagged with who caused it) and what `RoomMessages.send` would send the player;
 * - [botMove]: one move of the bot as `BotRooms.move` makes it (refused moves told to the bot, the bot conceding after
 *   `BotPlayer.MAX_ATTEMPTS` refusals) and what the player is sent of it;
 * - the [RoomHost] answers: `RoomMembership` (join, leave, kick, close), `RoomDecks` (the decks from [LocalDecks] instead
 *   of Mongo), `BotRooms` (the bot's level, ranked, pace, edge), `TestTimeline` (only in test rooms: refused here).
 *
 * Nothing here is shared between rooms or threads: a [LocalMatch] owns one, and calls it from one thread.
 */
internal class LocalRoom(
    val userId: String,
    private val avatar: String?,
    private val playmat: String?,
    roomId: String,
    seed: Long,
    private val decks: LocalDecks,
    botLevel: String?,
    /** The seed of each new bot's own chance (the copies it thinks on). */
    private val botSeed: Long,
) : RoomHost {

    val room: GameRoom = GameRoom()
    private val engine = RoomEngine(this)
    private var bot: BotPlayer
    /** False once the room is closed (left or closed by its owner): every action is answered "La sala ya no existe". */
    var open = true
        private set
    /** The instance ids of the cards loaded into the room ("c1", "c2", …; the server's are random UUIDs). */
    private var ids = 0

    init {
        // RoomService.createRoom(owner, RoomOptions.BOT): RoomSetup.newRoom, then BotRooms.seat
        room.hostId = userId
        room.confirmMandatory = true
        room.ranked = false
        room.emptySince = Platform.now()
        room.roomId = roomId
        room.random = GameRandom(seed)
        if (botLevel != null) room.botLevel = botLevel
        room.bot = true
        val seat = PlayerState(BOT, BotCatalog.nameOf(room.botLevel))
        seat.connected = true
        room.players[BOT] = seat
        bot = newBot(room.botLevel)
    }

    fun nextInstanceId(): String = "c" + (++ids)

    private fun newBot(level: String?): BotPlayer = BotCatalog.botAt(level).also { it.seed(botSeed) }

    // ------------------------------------------------------------------ The player's action

    /** The player's action (`RoomService.applyAndBroadcast`): the messages they are sent, in order (none or one). */
    fun send(action: GameAction): List<GameStateUpdate> {
        // The bots' pace, at once: nothing is sent unless it is refused (and the bot is not woken)
        if (open && ActionType.SET_BOT_DELAY.`is`(action)) {
            val refused = pace(room, userId, action) ?: return emptyList()
            return toPlayer(withActor(GameStateUpdate.error(room.roomId, refused), userId))
        }
        if (open) {
            room.manualPlayers.remove(userId)
            room.adminPlayers.remove(userId)
        }
        return toPlayer(applyAction(userId, action))
    }

    /** `RoomService.applyAction`: one action at a time; an error says who caused it. */
    private fun applyAction(actor: String, action: GameAction): GameStateUpdate? {
        val update = apply(actor, action)
        if (update != null && update.error != null && update.actorId == null) update.actorId = actor
        return update
    }

    private fun apply(actor: String, action: GameAction): GameStateUpdate? {
        if (!open) return GameStateUpdate.error(room.roomId, SimMessages.ROOM_GONE)
        val who = engine.actor(room, actor, action)
        if (who.update != null) return who.update
        return engine.apply(room, who.actor, action, false)
    }

    /**
     * `RoomMessages.send`, for the player alone: an error only to who caused it (as is); any other update, masked for
     * them ([StateMask], not a manual player: the site's editors' manual actions are the server's), to the room's players,
     * who caused it and who it is about, never to the bot.
     */
    private fun toPlayer(update: GameStateUpdate?): List<GameStateUpdate> {
        if (update == null) return emptyList()
        if (update.error != null) return if (update.actorId == userId) listOf(update) else emptyList()
        val viewers = LinkedHashSet<String?>(update.players?.keys ?: emptySet())
        if (update.actorId != null) viewers.add(update.actorId)
        if (update.subjectUserId != null) viewers.add(update.subjectUserId)
        viewers.remove(RoomEngine.EDITOR_RIVAL)
        viewers.remove(BOT)
        return if (userId in viewers) listOf(StateMask.forViewer(update, userId, false)) else emptyList()
    }

    private fun withActor(update: GameStateUpdate, actor: String): GameStateUpdate {
        update.actorId = actor
        return update
    }

    // ------------------------------------------------------------------ The bot

    /**
     * One move of the bot (`BotRooms.move`): null when it has nothing to do now; else what the player is sent of it (its
     * update, or the update of its conceding when the engine refused every move it tried).
     */
    fun botMove(): List<GameStateUpdate>? {
        if (!open || room.paused) return null
        for (attempt in 0 until BotPlayer.MAX_ATTEMPTS) {
            val action = bot.next(room, BOT) ?: return null
            val update = applyAction(BOT, action)
            if (update == null || update.error == null) {
                bot.played(room, action)
                return toPlayer(update)
            }
            bot.refused(action, update.error)
        }
        return concede()
    }

    /** A bot out of valid moves concedes (`BotRooms.concede`). */
    private fun concede(): List<GameStateUpdate> {
        val update = applyAction(BOT, BotPlayer.action(ActionType.SURRENDER, frozenMapOf()))
        if (update == null || update.error != null) return emptyList()
        update.logEntry = "El bot no encontró una jugada válida y se rindió"
        RoomEngine.recordLog(room, update, null)
        update.eventLog = ArrayList(room.eventLog)
        return toPlayer(update)
    }

    /** How long the server's bot waits before its next move (`BotRooms.wake`): the room's pace, else the bot's own. */
    fun botPauseMs(): Long = room.botDelayMs?.toLong() ?: bot.pause(room, BOT)

    // ------------------------------------------------------------------ RoomHost: RoomMembership

    override fun join(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        if (room.isFull() && !room.players.containsKey(userId)) return GameStateUpdate.error(room.roomId, SimMessages.ROOM_FULL)
        var name = action.getString("username")
        room.emptySince = null
        val existing = room.getPlayer(userId)
        if (existing != null) {
            existing.connected = true
            dress(existing)
            return GameStateUpdate.from(room, "RECONNECT", userId)
        }
        if (name == null) name = userId
        val player = PlayerState(userId, name)
        player.connected = true
        dress(player)
        room.players[userId!!] = player
        if (room.hostId == null) room.hostId = userId
        return GameStateUpdate.from(room, ActionType.JOIN_ROOM, userId)
    }

    /** The player's icon and playmat: the ones given to the match (the server looks them up in their profile). */
    private fun dress(player: PlayerState) {
        player.avatar = if (player.userId == userId) avatar else null
        player.playmat = if (player.userId == userId) playmat else null
    }

    /** A room against the bot is its owner's alone: leaving closes it (a ranked game left before its end is lost). */
    override fun leave(room: GameRoom, userId: String?): GameStateUpdate {
        if (room.getPlayer(userId) == null) return GameStateUpdate.notInRoom(room)
        if (room.ranked && RoomGuard.playing(room)) lostToTheBot(room)
        room.status = GameRoom.RoomStatus.FINISHED
        open = false
        return GameStateUpdate.from(room, "ROOM_CLOSED", userId)
    }

    override fun kick(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        if (userId != room.hostId) return GameStateUpdate.error(room.roomId, "Solo el dueño de la sala puede sacar jugadores")
        val targetId = action.getString("userId")
        val target = if (targetId == null) null else room.getPlayer(targetId)
        if (target == null || targetId == userId) return GameStateUpdate.error(room.roomId, SimMessages.PLAYER_NOT_FOUND)
        if (BOT == targetId) return GameStateUpdate.error(room.roomId, "El bot no puede salir de su sala")
        // Only the player and the bot are in the room: nobody else to take out
        return GameStateUpdate.error(room.roomId, SimMessages.PLAYER_NOT_FOUND)
    }

    override fun close(room: GameRoom, userId: String?): GameStateUpdate {
        if (userId != room.hostId) return GameStateUpdate.error(room.roomId, "Solo el dueño de la sala puede cerrarla")
        if (!room.editor && RoomGuard.inGame(room)) return GameStateUpdate.error(room.roomId, "La partida está en curso: ríndete para salir")
        room.status = GameRoom.RoomStatus.FINISHED
        val update = GameStateUpdate.from(room, "ROOM_CLOSED", userId)
        open = false
        return update
    }

    private fun lostToTheBot(room: GameRoom) {
        room.winnerId = BOT
        room.endReason = "abandon"
        room.status = GameRoom.RoomStatus.POST_GAME
    }

    // ------------------------------------------------------------------ RoomHost: BotRooms

    override fun botSetting(type: ActionType, room: GameRoom, userId: String?, action: GameAction): GameStateUpdate = when (type) {
        ActionType.SET_BOT_LEVEL -> setLevel(room, userId, action)
        ActionType.SET_EDITOR_BOT -> if (!room.editor) GameStateUpdate.error(room.roomId, "Solo en la sala de pruebas el bot juega el lado del rival")
            else GameStateUpdate.error(room.roomId, "Solo el dueño de la sala decide quién juega el rival")
        ActionType.SET_EDITOR_SELF_BOT -> GameStateUpdate.error(room.roomId, "Solo en la sala de pruebas un bot juega tu lado")
        ActionType.SET_BOT_DELAY -> {
            val refused = pace(room, userId, action)
            if (refused != null) GameStateUpdate.error(room.roomId, refused) else GameStateUpdate.from(room, ActionType.SET_BOT_DELAY, userId)
        }
        ActionType.SET_BOT_EDGE -> GameStateUpdate.error(room.roomId, "La ventaja del bot se elige solo en la sala de pruebas")
        ActionType.SET_RANKED -> setRanked(room, userId, action)
        else -> throw IllegalArgumentException("Not a bot setting: $type")
    }

    private fun setLevel(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val level = action.getString("level")
        if ("self" == action.getString("side")) return GameStateUpdate.error(room.roomId, "Solo en la sala de pruebas un bot juega tu lado")
        if (userId != room.hostId) return GameStateUpdate.error(room.roomId, "Solo el dueño de la sala elige el nivel del bot")
        if (room.ranked) return GameStateUpdate.error(room.roomId, "En una partida ranked el nivel del bot lo da tu rango")
        if (room.status != GameRoom.RoomStatus.WAITING) return GameStateUpdate.error(room.roomId, "El nivel se elige antes de empezar la partida")
        if (level == null || !BotCatalog.LEVELS.contains(level)) return GameStateUpdate.error(room.roomId, "Ese nivel no existe")
        room.botLevel = level
        bot = newBot(level)
        room.getPlayer(BOT)?.username = BotCatalog.nameOf(level)
        return GameStateUpdate.from(room, ActionType.SET_BOT_LEVEL, userId)
    }

    private fun setRanked(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        if (room.editor) return GameStateUpdate.error(room.roomId, "La sala de pruebas no es ranked")
        if (userId != room.hostId) return GameStateUpdate.error(room.roomId, "Solo el dueño de la sala elige el tipo de partida")
        if (room.status != GameRoom.RoomStatus.WAITING) return GameStateUpdate.error(room.roomId, "El tipo de partida se elige antes de empezar")
        if (!room.reserved.isEmpty()) return GameStateUpdate.error(room.roomId, "Una partida de la cola ranked siempre es ranked")
        val ranked = action.getBoolean("ranked", false)
        room.ranked = ranked
        room.players.values.forEach { p -> if (BOT != p.userId) p.ready = false }
        if (ranked) {
            room.firstChoice = "random"
            rankedBot(room)
        } else {
            room.botEdge = 0
        }
        return GameStateUpdate.from(room, ActionType.SET_RANKED, userId)
    }

    /** Ranked against the bot: at the level of a new player's strength (no ladder here), with a copy of the owner's deck. */
    private fun rankedBot(room: GameRoom) {
        val strength = RankRules.START_MMR
        val level = BotCatalog.levelFor(strength)
        room.botLevel = level
        room.botEdge = if (BotCatalog.HARD == level) BotEdge.levelFor(strength) else 0
        bot = newBot(level)
        val seat = room.getPlayer(BOT) ?: return
        val owner = room.getPlayer(room.hostId)
        seat.username = BotCatalog.nameOf(level)
        seat.deckId = null
        seat.deckName = null
        seat.deck = ArrayList()
        seat.originalDeck = ArrayList()
        seat.starterCardId = null
        if (owner?.deckId != null) {
            val copy = LocalDecks.freshCopy(owner.originalDeck) { nextInstanceId() }
            copy.forEach { it.finish = null }
            mirrorDeck(room, owner, copy)
        }
    }

    /** The bot plays the owner's deck until they choose another one for it (ranked: always the owner's deck). */
    private fun mirrorDeck(room: GameRoom, owner: PlayerState, deck: List<CardSlot>) {
        val seat = if (room.bot) room.getPlayer(BOT) else null
        if (seat == null || seat.deckId != null && !room.ranked || owner.userId == BOT) return
        seat.deckId = owner.deckId
        seat.deckName = owner.deckName
        seat.deck = ArrayList(deck)
        seat.originalDeck = PreRoomActions.deepCopySlots(deck)
        seat.starterCardId = deck.firstOrNull { it.starter }?.cardId
    }

    /** The pace of the bot's moves (`BotRooms.pace`): the refusal, or null. */
    private fun pace(room: GameRoom, userId: String?, action: GameAction): String? {
        if (!room.editor && !room.bot) return "Esta sala no tiene bots"
        if (userId != room.hostId) return "Solo el dueño de la sala elige el ritmo de los bots"
        val ms = action.payload?.get("ms")
        room.botDelayMs = if (ms is Number && ms.toInt() >= 0) minOf(10_000, ms.toInt()) else null
        return null
    }

    // ------------------------------------------------------------------ RoomHost: TestTimeline, RoomDecks

    override fun timeline(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate =
        GameStateUpdate.error(room.roomId, "Solo en la sala de pruebas")

    /** Pre-room: the deck a player will use (`RoomDecks.setDeck`), from the match's decks. */
    override fun setDeck(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        if (room.status != GameRoom.RoomStatus.WAITING) return GameStateUpdate.error(room.roomId, SimMessages.GAME_STARTED)
        val deckId = action.getString("deckId") ?: return GameStateUpdate.error(room.roomId, "Falta el mazo")
        val deck = decks.deck(deckId)
        if (deck == null || !RoomGuard.usableDeck(false, null, room, userId)) return GameStateUpdate.error(room.roomId, SimMessages.DECK_NOT_FOUND)
        val slots = LocalDecks.slots(deck) { nextInstanceId() }
        if (slots.isEmpty()) return GameStateUpdate.error(room.roomId, SimMessages.DECK_NOT_FOUND)
        val forBot = if (room.bot) LocalDecks.freshCopy(slots) { nextInstanceId() } else null
        if (!room.editor && LocalDecks.unprogrammedIn(slots) != null) return GameStateUpdate.error(room.roomId,
            "Este mazo tiene cartas que todavía no están programadas en el simulador")
        val isBot = BOT == userId
        player.deckId = deckId
        player.deckName = deck.name
        player.deck = ArrayList(slots)
        player.originalDeck = PreRoomActions.deepCopySlots(slots)
        player.starterCardId = slots.firstOrNull { it.starter }?.cardId
        player.ready = false
        if (room.bot && !isBot) mirrorDeck(room, player, forBot!!)
        return GameStateUpdate.from(room, ActionType.SET_DECK, userId)
    }

    override fun refreshCardMetadata(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val cardId = action.getString("cardId") ?: return GameStateUpdate.error(room.roomId, SimMessages.NO_CARD)
        val meta = decks.metadataOf(cardId) ?: return GameStateUpdate.error(room.roomId, SimMessages.METADATA_NOT_FOUND)
        LocalDecks.refresh(room, cardId, meta)
        return GameStateUpdate.from(room, ActionType.REFRESH_CARD_METADATA, userId)
    }

    companion object {
        const val BOT = BotSeat.BOT
    }
}

/**
 * What a match plays with on whatever thread calls it ([Clocks.using]): a fixed game time (or the platform's), the bots'
 * clock, and the bot's memory of its verdicts (`Usefulness`, else kept per thread: a match plays the same from its seed
 * whatever ran on the thread before it).
 */
internal class MatchClocks(private val fixedMillis: Long?, private val botNanos: (() -> Long)?) {
    private val memory = HashMap<String, Boolean>()

    fun <T> run(block: () -> T): T = Clocks.using(fixedMillis?.let { m -> { m } }, botNanos) { Usefulness.withMemory(memory, block) }
}
