package com.drive.drive_manager.simulator.room

import com.drive.drive_manager.simulator.platform.Clocks
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmRecord
import com.drive.drive_manager.simulator.engine.frozenMapOf
import com.drive.drive_manager.simulator.actions.ActionDispatcher
import com.drive.drive_manager.simulator.actions.TurnActions
import com.drive.drive_manager.simulator.bot.BotSeat
import com.drive.drive_manager.simulator.engine.EffectEngine

/**
 * The rules of a room, without the server around them: whose action it is (the test room's owner acting for its
 * rival, the bot's deck), the guards, the closed game state, the dispatch, and the history line. The server's
 * [RoomService] adds its rooms, locks, missions, recording and messages around it; the bot plays its copies
 * with it alone (`Sandbox`), and so will a game in the browser.
 */
class RoomEngine(host: RoomHost) {

    /** An action applied: what it changed, and who it counted as (the rival or the bot instead of the owner). */
    @JvmRecord
    data class Applied(val update: GameStateUpdate?, val actor: String?)

    private val dispatcher: ActionDispatcher = ActionDispatcher(host)

    /** Who `userId`'s action counts as, or an error update when they may not act in this room. */
    fun actor(room: GameRoom, userId: String?, action: GameAction): Applied {
        var userId = userId
        val type = ActionType.of(action)
        if (room.editor) {
            if (userId!! != room.hostId) return refused(room, "Es una sala de pruebas: solo su dueño puede jugar en ella", userId)
            // The owner plays the rival's side too
            if (EDITOR_RIVAL == action.getString("actAs") && (type == null || !type.ownerOnly())) userId = EDITOR_RIVAL
        }
        if (BotSeat.forBot(room, action)) {
            if (userId!! != room.hostId) return refused(room, "Solo el dueño de la sala elige el mazo del bot", userId)
            if (room.ranked) return refused(room, "En una partida ranked el bot juega con tu mismo mazo", userId)
            userId = BotSeat.BOT
        }
        return Applied(null, userId)
    }

    /** Applies the action of `actor` (as [actor] gave it): the guards, the dispatch, the history line. */
    fun apply(room: GameRoom, actor: String?, action: GameAction, manual: Boolean): GameStateUpdate? {
        val type = ActionType.of(action)
        val refusal = RoomGuard.refusal(room, actor, action, manual)
        if (refusal != null) return GameStateUpdate.error(room.roomId, refusal)

        // Closed game state (rule 7.2): while a chain or a choice is pending, only answering it (and leaving
        // or conceding) is allowed. The board's manual actions wait until the effect is done.
        if (room.isEngineBusy() && (type == null || !type.allowedWhileResolving())) {
            return GameStateUpdate.error(room.roomId, "Hay un efecto resolviéndose: espera a que termine")
        }

        var update: GameStateUpdate? = dispatcher.dispatch(type, room, actor, action)

        // "termina tu turno" (Última Línea De Defensa): once its combat and effects are over, the turn ends
        val current = room.currentTurnUserId
        if (update != null && update.error == null && current != null && !room.isEngineBusy()
                && room.status == GameRoom.RoomStatus.IN_PROGRESS && "MAIN" == room.currentPhase
                && room.usedThisTurn.remove(EffectEngine.END_TURN_REQUESTED + current)) {
            update = TurnActions.handleEnterEndPhase(room, current)
        }
        if (update != null && update.error == null) {
            room.version = room.version + 1
            recordLog(room, update, action)
            if (!room.headless) update.eventLog = ArrayList(room.eventLog)
        }
        return update
    }

    /** [actor] and [apply] together. */
    fun applyAction(room: GameRoom, userId: String?, action: GameAction, manual: Boolean): GameStateUpdate? {
        val who = actor(room, userId, action)
        if (who.update != null) return who.update
        return apply(room, who.actor, action, manual)
    }

    /**
     * A move of `seat`: its own in a room against the bot or online; in a test room the owner plays both sides,
     * so the rival's moves are the owner's acting for it.
     */
    fun applyAs(room: GameRoom, seat: String?, action: GameAction): GameStateUpdate? {
        if (!room.editor || seat!! == room.hostId) return applyAction(room, seat, action, false)
        return applyAction(room, room.hostId, asRival(action), false)
    }

    companion object {
        /** The test room's rival: its owner plays that side too. */
        const val EDITOR_RIVAL = "editor-rival"

        /** The same action, as the test room's owner acting for its rival. */
        @JvmStatic
        fun asRival(action: GameAction): GameAction {
            val payload: MutableMap<String, Any?> = LinkedHashMap(action.payload ?: frozenMapOf<String, Any?>())
            payload["actAs"] = EDITOR_RIVAL
            val rival = GameAction()
            rival.type = action.type
            rival.payload = HashMap(payload)
            return rival
        }

        /** An error says who caused it (errors go only to them): the update, tagged with `actor` when it is an untagged error. */
        @JvmStatic
        fun blamed(update: GameStateUpdate?, actor: String?): GameStateUpdate? {
            if (update != null && update.error != null && update.actorId == null) update.actorId = actor
            return update
        }

        /** A line of the history (an action by hand or of the room), with the card it was done to when there is one. */
        @JvmStatic
        fun recordLog(room: GameRoom, update: GameStateUpdate, action: GameAction?) {
            if (update.logEntry == null) return
            val entry = GameLogEntry(Clocks.millis(), update.logEntry)
            if (action != null) {
                entry.kind = "MANUAL"
                val id = action.getString("instanceId")
                val card = if (id == null) null else EffectEngine.findInRoom(room, id)
                if (card != null) {
                    entry.sourceId = id
                    entry.sourceName = card.cardName
                    entry.imageUrl = card.imageUrl
                }
            }
            room.eventLog.add(entry)
            if (room.eventLog.size > SimulatorLimits.EVENT_LOG_MAX) room.eventLog.removeAt(0)
        }

        private fun refused(room: GameRoom, message: String, userId: String?): Applied {
            return Applied(GameStateUpdate.error(room.roomId, message), userId)
        }
    }
}
