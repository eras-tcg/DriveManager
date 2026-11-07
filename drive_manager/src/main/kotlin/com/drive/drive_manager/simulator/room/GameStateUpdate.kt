package com.drive.drive_manager.simulator.room

import com.drive.drive_manager.simulator.bot.BotCatalog
import com.drive.drive_manager.simulator.engine.Chain
import com.drive.drive_manager.simulator.engine.Combat
import com.drive.drive_manager.simulator.engine.EffectEngine
import com.drive.drive_manager.simulator.engine.GameEvent
import com.drive.drive_manager.simulator.engine.LegalActions
import com.drive.drive_manager.simulator.engine.PendingEffect
import com.drive.drive_manager.simulator.engine.Prompt
import com.drive.drive_manager.simulator.engine.SpecialRequirements
import com.drive.drive_manager.simulator.engine.Summons

class GameStateUpdate {
    var roomId: String? = null
    var status: String? = null
    var players: MutableMap<String, PlayerState>? = null
    var hostId: String? = null
    /** A test room: its owner controls both sides (see GameRoom.editor). */
    @get:JvmName("isEditor")
    var editor: Boolean = false
    /** A room against the bot (see GameRoom.bot). */
    @get:JvmName("isBot")
    var bot: Boolean = false
    /** A ranked game (see GameRoom.ranked). */
    @get:JvmName("isRanked")
    var ranked: Boolean = false
    /** A room to watch a kept game again: read only (see GameRoom.replay). */
    @get:JvmName("isReplay")
    var replay: Boolean = false
    /** A room of the ranked queue: for its two players alone, always ranked (see GameRoom.reserved). */
    @get:JvmName("isQueued")
    var queued: Boolean = false
    /** A test room whose rival the bot plays (see GameRoom.editorBot). */
    @get:JvmName("isEditorBot")
    var editorBot: Boolean = false
    /** Sala de pruebas: a bot plays the owner's side too, at this level, and the bots' pause (see GameRoom). */
    @get:JvmName("isEditorSelfBot")
    var editorSelfBot: Boolean = false
    var selfBotLevel: String? = null
    var botDelayMs: Int? = null
    /** The bot's edge, 0–8 (BotEdge). */
    var botEdge: Int = 0
    /** Sala de pruebas: the bots wait; the step of the time line shown and how many there are (see TestTimeline). */
    @get:JvmName("isPaused")
    var paused: Boolean = false
    var timelineAt: Int? = null
    var timelineSize: Int? = null
    /** The bot's level in a room against it (see GameRoom.botLevel). */
    var botLevel: String? = null
    /** What the owner may choose for the bot: the levels, then the named models to try (id and label). */
    var botChoices: MutableList<MutableMap<String, String>>? = null
    /** Mulligan: who already confirmed their hand. */
    var mulliganReady: MutableList<String>? = null
    /** Pre-room: who takes the first turn ("random" or a player id). */
    var firstChoice: String? = null
    var currentTurnUserId: String? = null
    var currentPhase: String? = null
    var event: String? = null
    var actorId: String? = null
    var error: String? = null
    var sharedCounter: Int = 0
    var postGameChoices: MutableMap<String, String>? = null
    /** The player an event is about (the one taken out of the room by PLAYER_KICKED). */
    var subjectUserId: String? = null
    var targetedInstanceId: String? = null
    var targetingSourceInstanceId: String? = null
    var revealedCardImageUrl: String? = null
    var revealedCardName: String? = null
    var logEntry: String? = null
    var eventLog: MutableList<GameLogEntry>? = null
    /** Latest timeline events (structured). */
    var timeline: MutableList<GameEvent>? = null
    /** Effects in the chain (the last one resolves first). */
    var chain: MutableList<PendingEffect>? = null
    /** Choice being waited for; only prompt.playerId may answer. */
    var prompt: Prompt? = null
    /** Combat in progress, if any. */
    var combat: Combat? = null
    /** Who took the first turn. */
    var firstPlayerId: String? = null
    /** The player who can still use the "ventaja del iniciador", if any. */
    var starterAdvantageUserId: String? = null
    /** Slow effects each player can activate right now (rule 11.4.1.1), by player id. */
    var activatable: MutableMap<String, MutableList<Chain.Activatable>>? = null
    /** Players who already played a structure normally this turn (one per turn). */
    var structurePlayed: MutableList<String>? = null
    /** Cards each player can special summon now, from the hand or the Tributos, by player id. */
    var specialSummonable: MutableMap<String, MutableList<String>>? = null
    /** Utilities in each player's hand that cannot be played now (their "En Juego" cannot be activated), by player id. */
    var unplayable: MutableMap<String, MutableList<String>>? = null
    /** What each player can do now, by player id and then by card ([com.drive.drive_manager.simulator.engine.LegalActions]). */
    var legalActions: MutableMap<String, MutableMap<String, MutableList<String>>>? = null
    /** The enemies each attacker can attack, by player id and then by attacker. */
    var attackTargets: MutableMap<String, MutableMap<String, MutableList<String>>>? = null
    /** Who won the game that just ended, and why ("lives", "surrender"). */
    var winnerId: String? = null
    var endReason: String? = null

    companion object {
        /** The room after an action of that type (the update's event is the action's type). */
        @JvmStatic
        fun from(room: GameRoom, event: ActionType, actorId: String?): GameStateUpdate {
            return from(room, event.name, actorId)
        }

        @JvmStatic
        fun from(room: GameRoom, event: String?, actorId: String?): GameStateUpdate {
            // Continuous effects as they are now (manual moves in editor mode record no event)
            EffectEngine.refreshContinuous(room)
            val u = GameStateUpdate()
            u.roomId            = room.roomId
            u.status            = room.status.name
            u.players           = room.players
            u.hostId            = room.hostId
            u.editor            = room.editor
            u.bot               = room.bot
            u.ranked            = room.ranked
            u.replay            = room.replay
            u.queued            = !room.reserved.isEmpty()
            u.editorBot         = room.editorBot
            u.editorSelfBot     = room.editorSelfBot
            u.selfBotLevel      = if (room.editor) room.selfBotLevel else null
            u.botDelayMs        = room.botDelayMs
            u.botEdge           = room.botEdge
            u.paused            = room.paused
            u.timelineAt        = if (room.editor || room.replay) room.timelineAt else null
            u.timelineSize      = if (room.editor || room.replay) room.timelineSize else null
            u.botLevel          = if (room.bot || room.editor) room.botLevel else null
            u.botChoices        = if (room.editor || room.bot && room.status == GameRoom.RoomStatus.WAITING) BotCatalog.CHOICES else null
            u.mulliganReady     = if (room.status == GameRoom.RoomStatus.MULLIGAN) ArrayList(room.mulliganReady) else null
            u.firstChoice       = room.firstChoice
            u.currentTurnUserId = room.currentTurnUserId
            u.currentPhase      = room.currentPhase
            u.sharedCounter     = room.sharedCounter
            u.event             = event
            u.actorId           = actorId
            u.chain             = if (room.chain.isEmpty()) null else ArrayList(room.chain)
            u.prompt            = room.prompt
            u.combat            = room.combat
            u.firstPlayerId     = room.firstPlayerId
            u.starterAdvantageUserId = room.starterAdvantageUserId
            u.winnerId          = room.winnerId
            u.endReason         = room.endReason
            // A room only tried on: nobody reads what each player may do
            if (room.headless) return u
            val activatable = LinkedHashMap<String, MutableList<Chain.Activatable>>()
            for (id in room.players.keys) {
                val list = Chain.activatable(room, id)
                if (!list.isEmpty()) activatable[id] = list
            }
            u.activatable       = if (activatable.isEmpty()) null else activatable
            val specials = LinkedHashMap<String, MutableList<String>>()
            for (id in room.players.keys) {
                val list = SpecialRequirements.specialSummonable(room, id)
                if (!list.isEmpty()) specials[id] = list
            }
            // Always sent (empty when nobody can): the board relies on it instead of its own checks
            u.specialSummonable = specials
            val unplayable = LinkedHashMap<String, MutableList<String>>()
            for (id in room.players.keys) {
                val list = Summons.unplayable(room, id)
                if (!list.isEmpty()) unplayable[id] = list
            }
            u.unplayable        = unplayable
            val legalActions = LinkedHashMap<String, MutableMap<String, MutableList<String>>>()
            val attackTargets = LinkedHashMap<String, MutableMap<String, MutableList<String>>>()
            u.legalActions = legalActions
            u.attackTargets = attackTargets
            for (id in room.players.keys) {
                legalActions[id] = LegalActions.of(room, id)
                attackTargets[id] = LegalActions.attackTargets(room, id)
            }
            val structures = room.players.keys.stream().filter { id -> Summons.structurePlayed(room, id) }.toList()
            u.structurePlayed   = if (structures.isEmpty()) null else structures
            val tl = room.timeline
            u.timeline          = if (tl.isEmpty()) null else ArrayList(tl.subList(Math.max(0, tl.size - 100), tl.size))
            u.postGameChoices   = if (room.postGameChoices.isEmpty())
                                  null else LinkedHashMap(room.postGameChoices)
            return u
        }

        /** The error to an action of someone who is not one of the room's players. */
        @JvmStatic
        fun notInRoom(room: GameRoom): GameStateUpdate {
            return error(room.roomId, SimMessages.NOT_IN_ROOM)
        }

        @JvmStatic
        fun error(roomId: String?, message: String?): GameStateUpdate {
            val u = GameStateUpdate()
            u.roomId = roomId
            u.error  = message
            return u
        }
    }

    // --- What Lombok's @Data generated ---

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is GameStateUpdate) return false
        if (!other.canEqual(this)) return false
        if (editor != other.editor) return false
        if (bot != other.bot) return false
        if (ranked != other.ranked) return false
        if (replay != other.replay) return false
        if (queued != other.queued) return false
        if (editorBot != other.editorBot) return false
        if (editorSelfBot != other.editorSelfBot) return false
        if (botEdge != other.botEdge) return false
        if (paused != other.paused) return false
        if (sharedCounter != other.sharedCounter) return false
        if (botDelayMs != other.botDelayMs) return false
        if (timelineAt != other.timelineAt) return false
        if (timelineSize != other.timelineSize) return false
        if (roomId != other.roomId) return false
        if (status != other.status) return false
        if (players != other.players) return false
        if (hostId != other.hostId) return false
        if (selfBotLevel != other.selfBotLevel) return false
        if (botLevel != other.botLevel) return false
        if (botChoices != other.botChoices) return false
        if (mulliganReady != other.mulliganReady) return false
        if (firstChoice != other.firstChoice) return false
        if (currentTurnUserId != other.currentTurnUserId) return false
        if (currentPhase != other.currentPhase) return false
        if (event != other.event) return false
        if (actorId != other.actorId) return false
        if (error != other.error) return false
        if (postGameChoices != other.postGameChoices) return false
        if (subjectUserId != other.subjectUserId) return false
        if (targetedInstanceId != other.targetedInstanceId) return false
        if (targetingSourceInstanceId != other.targetingSourceInstanceId) return false
        if (revealedCardImageUrl != other.revealedCardImageUrl) return false
        if (revealedCardName != other.revealedCardName) return false
        if (logEntry != other.logEntry) return false
        if (eventLog != other.eventLog) return false
        if (timeline != other.timeline) return false
        if (chain != other.chain) return false
        if (prompt != other.prompt) return false
        if (combat != other.combat) return false
        if (firstPlayerId != other.firstPlayerId) return false
        if (starterAdvantageUserId != other.starterAdvantageUserId) return false
        if (activatable != other.activatable) return false
        if (structurePlayed != other.structurePlayed) return false
        if (specialSummonable != other.specialSummonable) return false
        if (unplayable != other.unplayable) return false
        if (legalActions != other.legalActions) return false
        if (attackTargets != other.attackTargets) return false
        if (winnerId != other.winnerId) return false
        if (endReason != other.endReason) return false
        return true
    }

    protected fun canEqual(other: Any?): Boolean = other is GameStateUpdate

    override fun hashCode(): Int {
        val PRIME = 59
        var result = 1
        result = result * PRIME + (if (editor) 79 else 97)
        result = result * PRIME + (if (bot) 79 else 97)
        result = result * PRIME + (if (ranked) 79 else 97)
        result = result * PRIME + (if (replay) 79 else 97)
        result = result * PRIME + (if (queued) 79 else 97)
        result = result * PRIME + (if (editorBot) 79 else 97)
        result = result * PRIME + (if (editorSelfBot) 79 else 97)
        result = result * PRIME + botEdge
        result = result * PRIME + (if (paused) 79 else 97)
        result = result * PRIME + sharedCounter
        val h_botDelayMs: Any? = botDelayMs
        result = result * PRIME + (h_botDelayMs?.hashCode() ?: 43)
        val h_timelineAt: Any? = timelineAt
        result = result * PRIME + (h_timelineAt?.hashCode() ?: 43)
        val h_timelineSize: Any? = timelineSize
        result = result * PRIME + (h_timelineSize?.hashCode() ?: 43)
        val h_roomId: Any? = roomId
        result = result * PRIME + (h_roomId?.hashCode() ?: 43)
        val h_status: Any? = status
        result = result * PRIME + (h_status?.hashCode() ?: 43)
        val h_players: Any? = players
        result = result * PRIME + (h_players?.hashCode() ?: 43)
        val h_hostId: Any? = hostId
        result = result * PRIME + (h_hostId?.hashCode() ?: 43)
        val h_selfBotLevel: Any? = selfBotLevel
        result = result * PRIME + (h_selfBotLevel?.hashCode() ?: 43)
        val h_botLevel: Any? = botLevel
        result = result * PRIME + (h_botLevel?.hashCode() ?: 43)
        val h_botChoices: Any? = botChoices
        result = result * PRIME + (h_botChoices?.hashCode() ?: 43)
        val h_mulliganReady: Any? = mulliganReady
        result = result * PRIME + (h_mulliganReady?.hashCode() ?: 43)
        val h_firstChoice: Any? = firstChoice
        result = result * PRIME + (h_firstChoice?.hashCode() ?: 43)
        val h_currentTurnUserId: Any? = currentTurnUserId
        result = result * PRIME + (h_currentTurnUserId?.hashCode() ?: 43)
        val h_currentPhase: Any? = currentPhase
        result = result * PRIME + (h_currentPhase?.hashCode() ?: 43)
        val h_event: Any? = event
        result = result * PRIME + (h_event?.hashCode() ?: 43)
        val h_actorId: Any? = actorId
        result = result * PRIME + (h_actorId?.hashCode() ?: 43)
        val h_error: Any? = error
        result = result * PRIME + (h_error?.hashCode() ?: 43)
        val h_postGameChoices: Any? = postGameChoices
        result = result * PRIME + (h_postGameChoices?.hashCode() ?: 43)
        val h_subjectUserId: Any? = subjectUserId
        result = result * PRIME + (h_subjectUserId?.hashCode() ?: 43)
        val h_targetedInstanceId: Any? = targetedInstanceId
        result = result * PRIME + (h_targetedInstanceId?.hashCode() ?: 43)
        val h_targetingSourceInstanceId: Any? = targetingSourceInstanceId
        result = result * PRIME + (h_targetingSourceInstanceId?.hashCode() ?: 43)
        val h_revealedCardImageUrl: Any? = revealedCardImageUrl
        result = result * PRIME + (h_revealedCardImageUrl?.hashCode() ?: 43)
        val h_revealedCardName: Any? = revealedCardName
        result = result * PRIME + (h_revealedCardName?.hashCode() ?: 43)
        val h_logEntry: Any? = logEntry
        result = result * PRIME + (h_logEntry?.hashCode() ?: 43)
        val h_eventLog: Any? = eventLog
        result = result * PRIME + (h_eventLog?.hashCode() ?: 43)
        val h_timeline: Any? = timeline
        result = result * PRIME + (h_timeline?.hashCode() ?: 43)
        val h_chain: Any? = chain
        result = result * PRIME + (h_chain?.hashCode() ?: 43)
        val h_prompt: Any? = prompt
        result = result * PRIME + (h_prompt?.hashCode() ?: 43)
        val h_combat: Any? = combat
        result = result * PRIME + (h_combat?.hashCode() ?: 43)
        val h_firstPlayerId: Any? = firstPlayerId
        result = result * PRIME + (h_firstPlayerId?.hashCode() ?: 43)
        val h_starterAdvantageUserId: Any? = starterAdvantageUserId
        result = result * PRIME + (h_starterAdvantageUserId?.hashCode() ?: 43)
        val h_activatable: Any? = activatable
        result = result * PRIME + (h_activatable?.hashCode() ?: 43)
        val h_structurePlayed: Any? = structurePlayed
        result = result * PRIME + (h_structurePlayed?.hashCode() ?: 43)
        val h_specialSummonable: Any? = specialSummonable
        result = result * PRIME + (h_specialSummonable?.hashCode() ?: 43)
        val h_unplayable: Any? = unplayable
        result = result * PRIME + (h_unplayable?.hashCode() ?: 43)
        val h_legalActions: Any? = legalActions
        result = result * PRIME + (h_legalActions?.hashCode() ?: 43)
        val h_attackTargets: Any? = attackTargets
        result = result * PRIME + (h_attackTargets?.hashCode() ?: 43)
        val h_winnerId: Any? = winnerId
        result = result * PRIME + (h_winnerId?.hashCode() ?: 43)
        val h_endReason: Any? = endReason
        result = result * PRIME + (h_endReason?.hashCode() ?: 43)
        return result
    }

    override fun toString(): String =
        "GameStateUpdate(roomId=${roomId}, status=${status}, players=${players}, hostId=${hostId}, editor=${editor}, bot=${bot}, ranked=${ranked}, replay=${replay}, queued=${queued}, editorBot=${editorBot}, editorSelfBot=${editorSelfBot}, selfBotLevel=${selfBotLevel}, botDelayMs=${botDelayMs}, botEdge=${botEdge}, paused=${paused}, timelineAt=${timelineAt}, timelineSize=${timelineSize}, botLevel=${botLevel}, botChoices=${botChoices}, mulliganReady=${mulliganReady}, firstChoice=${firstChoice}, currentTurnUserId=${currentTurnUserId}, currentPhase=${currentPhase}, event=${event}, actorId=${actorId}, error=${error}, sharedCounter=${sharedCounter}, postGameChoices=${postGameChoices}, subjectUserId=${subjectUserId}, targetedInstanceId=${targetedInstanceId}, targetingSourceInstanceId=${targetingSourceInstanceId}, revealedCardImageUrl=${revealedCardImageUrl}, revealedCardName=${revealedCardName}, logEntry=${logEntry}, eventLog=${eventLog}, timeline=${timeline}, chain=${chain}, prompt=${prompt}, combat=${combat}, firstPlayerId=${firstPlayerId}, starterAdvantageUserId=${starterAdvantageUserId}, activatable=${activatable}, structurePlayed=${structurePlayed}, specialSummonable=${specialSummonable}, unplayable=${unplayable}, legalActions=${legalActions}, attackTargets=${attackTargets}, winnerId=${winnerId}, endReason=${endReason})"
}
