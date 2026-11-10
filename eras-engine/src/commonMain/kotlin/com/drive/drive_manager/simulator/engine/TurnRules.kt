package com.drive.drive_manager.simulator.engine

import kotlin.jvm.JvmStatic
import com.drive.drive_manager.simulator.engine.Chain.advance
import com.drive.drive_manager.simulator.engine.EffectEngine.inFrontLine
import com.drive.drive_manager.simulator.engine.EffectEngine.keywordLevel
import com.drive.drive_manager.simulator.engine.EffectEngine.name
import com.drive.drive_manager.simulator.engine.EffectEngine.reactivable
import com.drive.drive_manager.simulator.engine.EffectEngine.record
import com.drive.drive_manager.simulator.engine.EffectEngine.rulesPrompt
import com.drive.drive_manager.simulator.engine.EffectStep.*
import com.drive.drive_manager.simulator.room.GameRoom

/** The start and the end of a turn (rule 8) as the engine sees them: moving up to the Línea frontal and the turn's own triggers. */
object TurnRules {

    // ------------------------------------------------------------------ Línea frontal

    /**
     * Rules 8.1.0.4 and 8.3.0.3: at the start of the turn and in the end phase the active player may move any of
     * their creatures from the Retaguardia to the Línea frontal, all in one action. Asks only if there are some.
     */
    @JvmStatic
    fun offerAdvance(room: GameRoom, userId: String?, startOfTurn: Boolean) {
        val player = room.getPlayer(userId)
        if (player == null || room.status != GameRoom.RoomStatus.IN_PROGRESS || room.prompt != null) return
        val back = player.field.filter { c -> "creature" == c.cardType && !inFrontLine(c) }
        if (back.isEmpty()) return
        val title = if (startOfTurn) "Inicio de turno" else "Final de turno"
        rulesPrompt(room, userId, PromptPurpose.ADVANCE.id(), "$title · Línea frontal",
                "Elige las criaturas de tu retaguardia que suben a la línea frontal (todas a la vez), o ninguna.",
                "A la línea frontal", back, back)
        room.prompt!!.max = back.size
    }

    /**
     * Rule 8.1: the start of the turn, after the gems, the draw and the reactivation: records it (start-of-turn
     * triggers), offers the move up to the Línea frontal and lets pending effects resolve.
     */
    @JvmStatic
    fun turnStarted(room: GameRoom, playerId: String?) {
        val player = room.getPlayer(playerId) ?: return
        record(room, GameEvent.TURN_START, playerId, null, null, null, null, null, "Inicio del turno de " + player.username)
        // Reactivar: "al inicio del turno: reactiva esta carta" (any turn)
        for (p in room.players.values) {
            for (c in p.field) {
                // A structure with Reactivar too (Geo Vigilante), not only a creature
                val exhausted = reactivable(c) || ("creature" != c.cardType && c.tapped)
                if (exhausted && keywordLevel(c, "Reactivar") != null) {
                    c.tapped = false
                    record(room, GameEvent.UNTAP, p.userId, c, null, null, null, frozenMapOf<String, Any?>("keyword", "Reactivar"),
                            name(c) + " se reactiva (Reactivar)")
                }
            }
        }
        offerAdvance(room, playerId, true)
        advance(room)
    }

    /**
     * Rule 8.3: the end phase, after the Contador: offers the move up to the Línea frontal and records it
     * (end-of-turn triggers resolve once the move up is answered).
     */
    @JvmStatic
    fun turnEnding(room: GameRoom, playerId: String?) {
        val player = room.getPlayer(playerId) ?: return
        record(room, GameEvent.TURN_END, playerId, null, null, null, null, null, "Final del turno de " + player.username)
        offerAdvance(room, playerId, false)
        advance(room)
    }
}
