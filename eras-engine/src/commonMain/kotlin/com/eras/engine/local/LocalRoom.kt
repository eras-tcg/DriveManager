package com.eras.engine.local

import com.drive.drive_manager.simulator.bot.BotCatalog
import com.drive.drive_manager.simulator.bot.BotPlayer
import com.drive.drive_manager.simulator.bot.BotSeat
import com.drive.drive_manager.simulator.bot.RoomBots
import com.drive.drive_manager.simulator.bot.Usefulness
import com.drive.drive_manager.simulator.engine.GameRandom
import com.drive.drive_manager.simulator.platform.Clocks
import com.drive.drive_manager.simulator.rank.RankRules
import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.DeckChoice
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.GameStateUpdate
import com.drive.drive_manager.simulator.room.PlayerState
import com.drive.drive_manager.simulator.room.RoomEngine
import com.drive.drive_manager.simulator.room.RoomHost
import com.drive.drive_manager.simulator.room.RoomSeats
import com.drive.drive_manager.simulator.room.RoomSetup
import com.drive.drive_manager.simulator.room.SimMessages
import com.drive.drive_manager.simulator.room.StateMask

/**
 * A room against the bot run in the browser exactly as the server runs one, for its one human player: the offline shell
 * around the room session that the server uses too (common code: [RoomSeats] for who is in the room, [DeckChoice] for the
 * decks, [RoomBots] for the bot's seat, settings and moves, [StateMask] for what the player is sent). What is left here
 * is what the server does with its registry, Mongo, threads and STOMP:
 * - [send]: `RoomService.applyAndBroadcast` for the player's action (the bots' pace at once, the dispatch with this room
 *   as the [RoomHost], the error tagged with who caused it) and what `RoomMessages.send` would send the player;
 * - [botMove]: one move of the bot ([RoomBots.move], as `BotRooms.move`) and what the player is sent of it;
 * - the hosts' answers: the player's icon and playmat from the match's configuration, the decks from [LocalDecks] instead
 *   of Mongo, the card instances numbered ("c1", "c2"…), a ranked game at a new player's strength.
 *
 * A test room (`editor`, "Sala de pruebas sin conexión"): the owner plays both sides, as the server's test room
 * (`RoomOptions.TEST`); the bot plays the rival's side only when the owner asks for it (SET_EDITOR_BOT). Its timeline
 * (`TestTimeline`) stays the server's.
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
    /** A test room: the owner plays both sides, no bot seated. */
    editor: Boolean = false,
) : RoomHost, RoomSeats.Keeper, DeckChoice.Shelf, RoomBots.Roster {

    /**
     * RoomService.createRoom(owner, RoomOptions.BOT): RoomSetup.newRoom, then the bot seated; a test room
     * (RoomOptions.TEST) comes with its rival, played by the owner.
     */
    val room: GameRoom = RoomSetup.newRoom(userId, editor, false)
    private val engine = RoomEngine(this)
    /** The bot of the bot's seat (in a test room, of the rival's side, once asked for), or null. */
    private var bot: BotPlayer? = null
    /** False once the room is closed (left or closed by its owner): every action is answered "La sala ya no existe". */
    var open = true
        private set
    /** The instance ids of the cards loaded into the room ("c1", "c2", …; the server's are random UUIDs). */
    private var ids = 0

    init {
        room.roomId = roomId
        room.random = GameRandom(seed)
        if (botLevel != null) room.botLevel = botLevel
        if (!editor) RoomBots.seat(room, this)
    }

    // ------------------------------------------------------------------ The player's action

    /** The player's action (`RoomService.applyAndBroadcast`): the messages they are sent, in order (none or one). */
    fun send(action: GameAction): List<GameStateUpdate> {
        // The bots' pace, at once: nothing is sent unless it is refused (and the bot is not woken)
        if (open && ActionType.SET_BOT_DELAY.`is`(action)) return toPlayer(RoomBots.paceAtOnce(room, userId, action))
        if (open) {
            room.manualPlayers.remove(userId)
            room.adminPlayers.remove(userId)
        }
        return toPlayer(applyAction(userId, action))
    }

    /** `RoomService.applyAction`: one action at a time; an error says who caused it. */
    private fun applyAction(actor: String, action: GameAction): GameStateUpdate? {
        if (!open) return RoomEngine.blamed(GameStateUpdate.error(room.roomId, SimMessages.ROOM_GONE), actor)
        return RoomEngine.blamed(engine.applyAction(room, actor, action, false), actor)
    }

    /** `RoomMessages.send`, for the player alone: masked for them ([StateMask], never a manual player: those are the server's). */
    private fun toPlayer(update: GameStateUpdate?): List<GameStateUpdate> {
        if (update == null || userId !in StateMask.recipients(room, update)) return emptyList()
        return listOf(if (update.error != null) update else StateMask.forViewer(update, userId, false))
    }

    // ------------------------------------------------------------------ The bot

    /**
     * One move of the bot (`BotRooms.move`): null when it has nothing to do now; else what the player is sent of it (its
     * update, or the update of its conceding when the engine refused every move it tried).
     */
    fun botMove(): List<GameStateUpdate>? {
        val player = bot
        if (!open || player == null) return null
        // Its seat: the bot's, or in a test room the rival's ([BotSeat.seatOf])
        val seat = BotSeat.seatOf(room)
        val sent = ArrayList<GameStateUpdate>()
        val move = RoomBots.move(room, player, seat, { applyAction(seat, it) }) { sent.addAll(toPlayer(it)) }
        return if (move == RoomBots.Move.NOTHING) null else sent
    }

    /**
     * How long the server's bot waits before its next move (`BotRooms.wake`): the room's pace, else the bot's own (a test
     * room without its bot: the default pace, nothing moves anyway).
     */
    fun botPauseMs(): Long = room.botDelayMs?.toLong() ?: bot?.pause(room, BotSeat.seatOf(room)) ?: DEFAULT_PAUSE_MS

    // ------------------------------------------------------------------ RoomHost: the room session's answers

    override fun join(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate = RoomSeats.join(room, userId, action, this)
    override fun leave(room: GameRoom, userId: String?): GameStateUpdate = RoomSeats.leave(room, userId, this)
    override fun kick(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate = RoomSeats.kick(room, userId, action, this)
    override fun close(room: GameRoom, userId: String?): GameStateUpdate = RoomSeats.close(room, userId, this)
    override fun botSetting(type: ActionType, room: GameRoom, userId: String?, action: GameAction): GameStateUpdate =
        RoomBots.setting(type, room, userId, action, this)
    override fun setDeck(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate = DeckChoice.setDeck(room, userId, action, this)
    override fun refreshCardMetadata(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate =
        DeckChoice.refreshCardMetadata(room, userId, action, this)

    /** Test rooms are the server's (`TestTimeline`). */
    override fun timeline(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate =
        GameStateUpdate.error(room.roomId, "Solo en la sala de pruebas")

    // ------------------------------------------------------------------ What the room session asks of this shell

    /** The player's icon and playmat: the ones given to the match (the server looks them up in their profile). */
    override fun dress(player: PlayerState) {
        player.avatar = if (player.userId == userId) avatar else null
        player.playmat = if (player.userId == userId) playmat else null
    }

    override fun closed(room: GameRoom) {
        open = false
    }

    override fun find(deckId: String): DeckChoice.Found? {
        val deck = decks.deck(deckId) ?: return null
        return DeckChoice.Found(deck.name, false, null) { LocalDecks.slots(deck) { nextInstanceId() } }
    }

    override fun refresh(room: GameRoom, cardId: String): Boolean {
        val meta = decks.metadataOf(cardId) ?: return false
        LocalDecks.refresh(room, cardId, meta)
        return true
    }

    override fun nextInstanceId(): String = "c" + (++ids)

    override fun newBot(room: GameRoom, level: String?) {
        bot = BotCatalog.botAt(level).also { it.seed(botSeed) }
    }

    /** Test room: the owner plays the rival's side by hand again. */
    override fun dropBot(room: GameRoom) {
        bot = null
    }

    /** No ladder here: a ranked game is played at a new player's strength, and nothing is recorded. */
    override fun ownerStrength(room: GameRoom): Int = RankRules.START_MMR

    companion object {
        const val BOT = BotSeat.BOT
        /** The pace asked of a test room with no bot (nothing moves; the worker only needs a number). */
        const val DEFAULT_PAUSE_MS = 600L
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
