package com.drive.drive_manager.simulator.room

import kotlin.jvm.JvmName
import com.drive.drive_manager.simulator.platform.Transient
import com.drive.drive_manager.simulator.platform.Platform
import com.drive.drive_manager.simulator.platform.Instant
import com.drive.drive_manager.simulator.engine.frozenSetOf
import com.drive.drive_manager.simulator.engine.GameRandom
import com.drive.drive_manager.simulator.bot.BotCatalog
import com.drive.drive_manager.simulator.engine.Combat
import com.drive.drive_manager.simulator.engine.FieldGrant
import com.drive.drive_manager.simulator.engine.GameEvent
import com.drive.drive_manager.simulator.engine.PendingAscension
import com.drive.drive_manager.simulator.engine.PendingEffect
import com.drive.drive_manager.simulator.engine.PendingRemoval
import com.drive.drive_manager.simulator.engine.Prompt
import com.drive.drive_manager.simulator.replay.MatchSetup

/**
 * A room and the game played in it, as sent to the board (field names and order are its JSON). The fields marked
 * [RoomSetting] belong to the room and stay when the Sala de pruebas travels in time; the rest are the game's.
 */
class GameRoom {

    enum class RoomStatus {
        WAITING, SELECTING_STARTER, MULLIGAN, IN_PROGRESS, POST_GAME, FINISHED;

        /** A game in course: from choosing the starters to its end. */
        fun isPlaying(): Boolean {
            return this == SELECTING_STARTER || this == MULLIGAN || this == IN_PROGRESS
        }
    }

    @field:RoomSetting
    var roomId: String? = null
    /**
     * A test room (editor mode): its owner plays alone, controlling both sides (the rival is not a real player),
     * with the manual actions and test cards. Not listed and nobody else can enter.
     */
    @field:RoomSetting @get:JvmName("isEditor")
    var editor: Boolean = false
    /**
     * A room against the bot: its owner plays one side and the server's bot the other (seat [BotRooms.BOT]).
     * Not listed and nobody else can enter; the owner picks the bot's deck.
     */
    @field:RoomSetting @get:JvmName("isBot")
    var bot: Boolean = false
    /**
     * A ranked game (online or against the bot): its result moves the players' rank. Chosen by the owner before the
     * game; who goes first is drawn, and against the bot its level and deck are not chosen. Not for test rooms.
     */
    @field:RoomSetting @get:JvmName("isRanked")
    var ranked: Boolean = false
    /** A room to watch a kept game again (ReplayRooms): read only, everything shown to its viewer (the host). */
    @field:RoomSetting @get:JvmName("isReplay")
    var replay: Boolean = false
    /** A room of the ranked queue: only these two players may enter it (not listed, always ranked). Empty: open. */
    @field:RoomSetting
    var reserved: MutableSet<String> = frozenSetOf()
    /** A test room (editor) whose rival side the bot plays: the owner tests it seeing everything (BotRooms#setEditorBot). */
    @field:RoomSetting @get:JvmName("isEditorBot")
    var editorBot: Boolean = false
    /** A test room whose owner's own side a bot plays too, at selfBotLevel (BotRooms#setEditorSelfBot). */
    @field:RoomSetting @get:JvmName("isEditorSelfBot")
    var editorSelfBot: Boolean = false
    @field:RoomSetting
    var selfBotLevel: String? = BotCatalog.NORMAL
    /** Grows with every action accepted: a bot that thought on an older copy of the room thinks again. */
    @field:RoomSetting
    var version: Long = 0
    /** Sala de pruebas: the bots wait (the owner paused them, or went back in time). */
    @field:RoomSetting @kotlin.concurrent.Volatile @get:JvmName("isPaused")
    var paused: Boolean = false
    /** Sala de pruebas: the step of the time line shown (from 0) and how many there are (see TestTimeline). */
    @field:RoomSetting
    var timelineAt: Int = -1
    @field:RoomSetting
    var timelineSize: Int = 0
    /** The pause before each move of the room's bots, chosen by the owner (null: each bot's own). */
    @field:RoomSetting @kotlin.concurrent.Volatile
    var botDelayMs: Int? = null
    /** The bot's edge, 0–8 (BotEdge): Modo solo ranked from the owner's MMR, or a test room's choice for its rival. */
    @field:RoomSetting
    var botEdge: Int = 0
    /** How well the bot plays, chosen by the owner before the game: "easy", "normal" or "hard" (see BotRooms). */
    @field:RoomSetting
    var botLevel: String? = BotCatalog.NORMAL
    /**
     * A lone mandatory effect is also shown to its controller to chain it (they see what triggered): rooms created by
     * the service; the engine's tests leave it off and chain it straight away.
     */
    @field:RoomSetting @get:JvmName("isConfirmMandatory")
    var confirmMandatory: Boolean = false
    var status: RoomStatus = RoomStatus.WAITING
    var players: MutableMap<String, PlayerState> = LinkedHashMap()
    @field:RoomSetting
    var hostId: String? = null
    var currentTurnUserId: String? = null
    var currentPhase: String? = "MAIN"
    var sharedCounter: Int = 0
    @field:RoomSetting
    var createdAt: Instant? = Platform.now()
    /** Pre-room: who takes the first turn, chosen by the host ("random" or a player id). */
    var firstChoice: String? = "random"
    /** Since when nobody is connected (null while someone is): the room is kept a while so reloading does not lose it. */
    @field:RoomSetting
    var emptySince: Instant? = null

    var starterSelections: MutableMap<String, String> = LinkedHashMap()
    var mulliganReady: MutableSet<String> = HashSet()
    /** Players who may use the manual actions in this online game (site editors): they see their own deck and lives. */
    @field:RoomSetting
    var manualPlayers: MutableSet<String> = HashSet()
    /** Players with the simulator's administration permission (simulator_admin): a bot may play their own side. */
    @field:RoomSetting
    var adminPlayers: MutableSet<String> = HashSet()
    var postGameChoices: MutableMap<String, String> = LinkedHashMap()

    // Persists for the room's lifetime (not the client's) — survives a player
    // navigating away and reconnecting. Lost only when the room itself ends.
    var eventLog: MutableList<GameLogEntry> = ArrayList()

    // --- Rules engine (simulator/engine) ---
    /** Structured timeline of rules-relevant events, in order (rules 11.6, 11.7). */
    var timeline: MutableList<GameEvent> = ArrayList()
    var eventSeq: Long = 0
    /** Effects in the chain; the last one resolves first (rule 11.3.0.6). */
    var chain: MutableList<PendingEffect> = ArrayList()
    /** Choice the engine is waiting for, if any. */
    var prompt: Prompt? = null
    /** Triggered effects waiting to be activated into the chain, in rules order (rule 11.7.0.5). */
    var triggerQueue: MutableList<PendingEffect> = ArrayList()
    /** Events since the last chain window: they trigger effects once the current resolution ends (rule 11.6.0.4). */
    var pendingEvents: MutableList<GameEvent> = ArrayList()
    var idSeq: Long = 0
    /** Removals waiting for their owner to choose a replacement, in order. */
    var pendingRemovals: MutableList<PendingRemoval> = ArrayList()
    /** An Ascenso that waits for the attack its summoner declared before it (Príncipe Charles). */
    var pendingAscension: PendingAscension? = null
    /** Cards whose owner already chose not to replace their removal: it happens now. */
    var removalDecided: MutableSet<String> = HashSet()
    /** The combat in progress (rule 10), if any. */
    var combat: Combat? = null
    /** "Todos tus aliados … ganan …": what applies to a player's side of the table until the turn ends. */
    var fieldGrants: MutableList<FieldGrant> = ArrayList()
    /** Effects already used this turn (rule 11.8), keyed by the engine. Cleared when the turn changes. */
    var usedThisTurn: MutableSet<String> = HashSet()
    /** Who took the first turn of the game (rule 3.0.1). */
    var firstPlayerId: String? = null
    /** The first turn of the game is in progress: no Definitive effect can be activated (designer ruling 2026-10-30). */
    @get:JvmName("isFirstTurn")
    var firstTurn: Boolean = false
    /** The second player while they still have the "ventaja del iniciador" (rule 6.2.1.4); null once used. */
    var starterAdvantageUserId: String? = null
    /** "Terminar turno" from the main phase went through the end phase and waits for its move up to be answered. */
    @get:JvmName("isEndTurnPending")
    var endTurnPending: Boolean = false
    /** The start of the turn waits for its move up to be answered before the main phase. */
    @get:JvmName("isStartPhasePending")
    var startPhasePending: Boolean = false
    /** Who won the last game, and why: "lives" (the opponent ran out of lives) or "surrender". */
    var winnerId: String? = null
    var endReason: String? = null

    /**
     * Where the room's chance comes from: every shuffle and random pick of the game. A room's own, so a game can be
     * played again exactly from a seed (the bot arena plays paired games); not part of the state sent to players.
     */
    @field:RoomSetting @Transient
    var random: GameRandom? = GameRandom()

    /**
     * A room only tried on (the bot's copies, a game played again): its updates carry no view for players (what may be
     * played, legal actions…), as nobody reads them; copied with the room.
     */
    @field:RoomSetting @Transient @get:JvmName("isHeadless")
    var headless: Boolean = false

    /** Only in the bot's copies: the player whose searches of the deck's top find the best valid cards (engine/SearchHope). */
    @Transient
    var searchHope: String? = null

    /** How the game in course started (its seed, seats and decks), to record and play it again (MatchRecorder). */
    var startSetup: MatchSetup? = null


    /** Closed game state (rule 7.2): a chain or a choice is pending, so no new actions can start. */
    fun isEngineBusy(): Boolean {
        return prompt != null || !chain.isEmpty() || !triggerQueue.isEmpty() || combat != null || !pendingRemovals.isEmpty() || pendingAscension != null
    }

    fun nextId(prefix: String?): String {
        return prefix + "-" + (++idSeq)
    }

    fun isFull(): Boolean {
        return players.size >= 2
    }

    fun getPlayer(userId: String?): PlayerState? {
        return players[userId]
    }

    fun getOpponentId(userId: String?): String? {
        return players.keys.firstOrNull { id -> id != userId }
    }

    // --- What Lombok's @Data generated ---

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is GameRoom) return false
        if (!other.canEqual(this)) return false
        if (editor != other.editor) return false
        if (bot != other.bot) return false
        if (ranked != other.ranked) return false
        if (replay != other.replay) return false
        if (editorBot != other.editorBot) return false
        if (editorSelfBot != other.editorSelfBot) return false
        if (version != other.version) return false
        if (paused != other.paused) return false
        if (timelineAt != other.timelineAt) return false
        if (timelineSize != other.timelineSize) return false
        if (botEdge != other.botEdge) return false
        if (confirmMandatory != other.confirmMandatory) return false
        if (sharedCounter != other.sharedCounter) return false
        if (eventSeq != other.eventSeq) return false
        if (idSeq != other.idSeq) return false
        if (firstTurn != other.firstTurn) return false
        if (endTurnPending != other.endTurnPending) return false
        if (startPhasePending != other.startPhasePending) return false
        if (botDelayMs != other.botDelayMs) return false
        if (roomId != other.roomId) return false
        if (reserved != other.reserved) return false
        if (selfBotLevel != other.selfBotLevel) return false
        if (botLevel != other.botLevel) return false
        if (status != other.status) return false
        if (players != other.players) return false
        if (hostId != other.hostId) return false
        if (currentTurnUserId != other.currentTurnUserId) return false
        if (currentPhase != other.currentPhase) return false
        if (createdAt != other.createdAt) return false
        if (firstChoice != other.firstChoice) return false
        if (emptySince != other.emptySince) return false
        if (starterSelections != other.starterSelections) return false
        if (mulliganReady != other.mulliganReady) return false
        if (manualPlayers != other.manualPlayers) return false
        if (adminPlayers != other.adminPlayers) return false
        if (postGameChoices != other.postGameChoices) return false
        if (eventLog != other.eventLog) return false
        if (timeline != other.timeline) return false
        if (chain != other.chain) return false
        if (prompt != other.prompt) return false
        if (triggerQueue != other.triggerQueue) return false
        if (pendingEvents != other.pendingEvents) return false
        if (pendingRemovals != other.pendingRemovals) return false
        if (pendingAscension != other.pendingAscension) return false
        if (removalDecided != other.removalDecided) return false
        if (combat != other.combat) return false
        if (fieldGrants != other.fieldGrants) return false
        if (usedThisTurn != other.usedThisTurn) return false
        if (firstPlayerId != other.firstPlayerId) return false
        if (starterAdvantageUserId != other.starterAdvantageUserId) return false
        if (winnerId != other.winnerId) return false
        if (endReason != other.endReason) return false
        if (startSetup != other.startSetup) return false
        return true
    }

    protected fun canEqual(other: Any?): Boolean = other is GameRoom

    override fun hashCode(): Int {
        val PRIME = 59
        var result = 1
        result = result * PRIME + (if (editor) 79 else 97)
        result = result * PRIME + (if (bot) 79 else 97)
        result = result * PRIME + (if (ranked) 79 else 97)
        result = result * PRIME + (if (replay) 79 else 97)
        result = result * PRIME + (if (editorBot) 79 else 97)
        result = result * PRIME + (if (editorSelfBot) 79 else 97)
        val h_version = version
        result = result * PRIME + (h_version ushr 32 xor h_version).toInt()
        result = result * PRIME + (if (paused) 79 else 97)
        result = result * PRIME + timelineAt
        result = result * PRIME + timelineSize
        result = result * PRIME + botEdge
        result = result * PRIME + (if (confirmMandatory) 79 else 97)
        result = result * PRIME + sharedCounter
        val h_eventSeq = eventSeq
        result = result * PRIME + (h_eventSeq ushr 32 xor h_eventSeq).toInt()
        val h_idSeq = idSeq
        result = result * PRIME + (h_idSeq ushr 32 xor h_idSeq).toInt()
        result = result * PRIME + (if (firstTurn) 79 else 97)
        result = result * PRIME + (if (endTurnPending) 79 else 97)
        result = result * PRIME + (if (startPhasePending) 79 else 97)
        val h_botDelayMs: Any? = botDelayMs
        result = result * PRIME + (h_botDelayMs?.hashCode() ?: 43)
        val h_roomId: Any? = roomId
        result = result * PRIME + (h_roomId?.hashCode() ?: 43)
        val h_reserved: Any? = reserved
        result = result * PRIME + (h_reserved?.hashCode() ?: 43)
        val h_selfBotLevel: Any? = selfBotLevel
        result = result * PRIME + (h_selfBotLevel?.hashCode() ?: 43)
        val h_botLevel: Any? = botLevel
        result = result * PRIME + (h_botLevel?.hashCode() ?: 43)
        val h_status: Any? = status
        result = result * PRIME + (h_status?.hashCode() ?: 43)
        val h_players: Any? = players
        result = result * PRIME + (h_players?.hashCode() ?: 43)
        val h_hostId: Any? = hostId
        result = result * PRIME + (h_hostId?.hashCode() ?: 43)
        val h_currentTurnUserId: Any? = currentTurnUserId
        result = result * PRIME + (h_currentTurnUserId?.hashCode() ?: 43)
        val h_currentPhase: Any? = currentPhase
        result = result * PRIME + (h_currentPhase?.hashCode() ?: 43)
        val h_createdAt: Any? = createdAt
        result = result * PRIME + (h_createdAt?.hashCode() ?: 43)
        val h_firstChoice: Any? = firstChoice
        result = result * PRIME + (h_firstChoice?.hashCode() ?: 43)
        val h_emptySince: Any? = emptySince
        result = result * PRIME + (h_emptySince?.hashCode() ?: 43)
        val h_starterSelections: Any? = starterSelections
        result = result * PRIME + (h_starterSelections?.hashCode() ?: 43)
        val h_mulliganReady: Any? = mulliganReady
        result = result * PRIME + (h_mulliganReady?.hashCode() ?: 43)
        val h_manualPlayers: Any? = manualPlayers
        result = result * PRIME + (h_manualPlayers?.hashCode() ?: 43)
        val h_adminPlayers: Any? = adminPlayers
        result = result * PRIME + (h_adminPlayers?.hashCode() ?: 43)
        val h_postGameChoices: Any? = postGameChoices
        result = result * PRIME + (h_postGameChoices?.hashCode() ?: 43)
        val h_eventLog: Any? = eventLog
        result = result * PRIME + (h_eventLog?.hashCode() ?: 43)
        val h_timeline: Any? = timeline
        result = result * PRIME + (h_timeline?.hashCode() ?: 43)
        val h_chain: Any? = chain
        result = result * PRIME + (h_chain?.hashCode() ?: 43)
        val h_prompt: Any? = prompt
        result = result * PRIME + (h_prompt?.hashCode() ?: 43)
        val h_triggerQueue: Any? = triggerQueue
        result = result * PRIME + (h_triggerQueue?.hashCode() ?: 43)
        val h_pendingEvents: Any? = pendingEvents
        result = result * PRIME + (h_pendingEvents?.hashCode() ?: 43)
        val h_pendingRemovals: Any? = pendingRemovals
        result = result * PRIME + (h_pendingRemovals?.hashCode() ?: 43)
        val h_pendingAscension: Any? = pendingAscension
        result = result * PRIME + (h_pendingAscension?.hashCode() ?: 43)
        val h_removalDecided: Any? = removalDecided
        result = result * PRIME + (h_removalDecided?.hashCode() ?: 43)
        val h_combat: Any? = combat
        result = result * PRIME + (h_combat?.hashCode() ?: 43)
        val h_fieldGrants: Any? = fieldGrants
        result = result * PRIME + (h_fieldGrants?.hashCode() ?: 43)
        val h_usedThisTurn: Any? = usedThisTurn
        result = result * PRIME + (h_usedThisTurn?.hashCode() ?: 43)
        val h_firstPlayerId: Any? = firstPlayerId
        result = result * PRIME + (h_firstPlayerId?.hashCode() ?: 43)
        val h_starterAdvantageUserId: Any? = starterAdvantageUserId
        result = result * PRIME + (h_starterAdvantageUserId?.hashCode() ?: 43)
        val h_winnerId: Any? = winnerId
        result = result * PRIME + (h_winnerId?.hashCode() ?: 43)
        val h_endReason: Any? = endReason
        result = result * PRIME + (h_endReason?.hashCode() ?: 43)
        val h_startSetup: Any? = startSetup
        result = result * PRIME + (h_startSetup?.hashCode() ?: 43)
        return result
    }

    override fun toString(): String =
        "GameRoom(roomId=${roomId}, editor=${editor}, bot=${bot}, ranked=${ranked}, replay=${replay}, reserved=${reserved}, editorBot=${editorBot}, editorSelfBot=${editorSelfBot}, selfBotLevel=${selfBotLevel}, version=${version}, paused=${paused}, timelineAt=${timelineAt}, timelineSize=${timelineSize}, botDelayMs=${botDelayMs}, botEdge=${botEdge}, botLevel=${botLevel}, confirmMandatory=${confirmMandatory}, status=${status}, players=${players}, hostId=${hostId}, currentTurnUserId=${currentTurnUserId}, currentPhase=${currentPhase}, sharedCounter=${sharedCounter}, createdAt=${createdAt}, firstChoice=${firstChoice}, emptySince=${emptySince}, starterSelections=${starterSelections}, mulliganReady=${mulliganReady}, manualPlayers=${manualPlayers}, adminPlayers=${adminPlayers}, postGameChoices=${postGameChoices}, eventLog=${eventLog}, timeline=${timeline}, eventSeq=${eventSeq}, chain=${chain}, prompt=${prompt}, triggerQueue=${triggerQueue}, pendingEvents=${pendingEvents}, idSeq=${idSeq}, pendingRemovals=${pendingRemovals}, pendingAscension=${pendingAscension}, removalDecided=${removalDecided}, combat=${combat}, fieldGrants=${fieldGrants}, usedThisTurn=${usedThisTurn}, firstPlayerId=${firstPlayerId}, firstTurn=${firstTurn}, starterAdvantageUserId=${starterAdvantageUserId}, endTurnPending=${endTurnPending}, startPhasePending=${startPhasePending}, winnerId=${winnerId}, endReason=${endReason}, random=${random}, headless=${headless}, searchHope=${searchHope}, startSetup=${startSetup})"
}
