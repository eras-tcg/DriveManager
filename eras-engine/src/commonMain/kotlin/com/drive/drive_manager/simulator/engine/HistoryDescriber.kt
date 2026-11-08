package com.drive.drive_manager.simulator.engine

import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmRecord
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameLogEntry
import com.drive.drive_manager.simulator.room.GameRoom

/**
 * What each line of the history is about, for the board: its event's type, the card behind it (the effect's card for an
 * effect's steps), the Recurso of an inherited effect, and whether it is one of an effect's steps (activations and
 * resolutions stand on their own; the steps hide in the short view, rule 11: "disparo, activación y resolución").
 */
object HistoryDescriber {

    /** An effect's own moments, not its steps. */
    private val EFFECT_MOMENTS: Set<String> = frozenSetOf(GameEvent.EFFECT_ACTIVATED, GameEvent.EFFECT_RESOLVED, GameEvent.EFFECT_DECLINED)

    @JvmStatic
    fun describe(room: GameRoom, entry: GameLogEntry) {
        val event = EffectEngine.eventAt(room, entry.eventSeq)
        if (event == null) {
            // A note while an effect resolves or pays its cost ("envía el resto al fondo de su mazo"): one of its steps
            val top = if (room.chain.isEmpty()) null else room.chain[room.chain.size - 1]
            if (top != null) {
                source(room, entry, top.sourceInstanceId, top.sourceName)
                entry.detail = true
            }
            return
        }
        entry.kind = event.type
        var sourceId = event.cardInstanceId
        var sourceName = event.cardName
        var scriptId: String? = null
        if (event.effectId != null) {
            val whose = whose(room, event.effectId!!)
            if (whose != null) {
                sourceId = whose.sourceId
                sourceName = whose.sourceName
                scriptId = whose.scriptId
            }
            val regulatory = event.data != null && event.data!!["regulatory"] == true
            entry.detail = !regulatory && !EFFECT_MOMENTS.contains(event.type!!)
        }
        val card = source(room, entry, sourceId, sourceName)
        inheritedFrom(room, entry, card, scriptId)
    }

    @JvmRecord
    private data class Whose(val sourceId: String?, val sourceName: String?, val scriptId: String?)

    /** Whose effect it is: still pending (chain, triggers), else what its activation recorded. */
    private fun whose(room: GameRoom, effectId: String): Whose? {
        val pending = ArrayList<PendingEffect>(room.chain)
        pending.addAll(room.triggerQueue)
        for (e in pending) {
            if (effectId == e.id) return Whose(e.sourceInstanceId, e.sourceName, e.scriptId)
        }
        var found: Whose? = null
        for (e in room.timeline) {
            if (GameEvent.EFFECT_ACTIVATED == e.type && effectId == e.effectId && e.data != null) {
                found = Whose(e.data!!["sourceId"] as String?, e.data!!["sourceName"] as String?, e.data!!["scriptId"] as String?)
            }
        }
        return found
    }

    /** The card behind the line, with its image. */
    private fun source(room: GameRoom, entry: GameLogEntry, sourceId: String?, sourceName: String?): CardSlot? {
        entry.sourceId = sourceId
        entry.sourceName = sourceName
        val card = if (sourceId == null) null else EffectEngine.findInRoom(room, sourceId)
        if (card != null) entry.imageUrl = card.imageUrl
        return card
    }

    /** An inherited effect: the Recurso that gives it (on the card, else a card of that name in the room, for its image). */
    private fun inheritedFrom(room: GameRoom, entry: GameLogEntry, card: CardSlot?, scriptId: String?) {
        val from = (if (scriptId == null) null else CardEffects.inheritedFrom(scriptId)) ?: return
        var resource = if (card == null) null
                else card.resources!!.firstOrNull { r -> from.equals(r.cardName, ignoreCase = true) }
        if (resource == null) resource = EffectEngine.cardsInRoom(room).firstOrNull { c -> from.equals(c.cardName, ignoreCase = true) }
        entry.inheritedFromName = if (resource != null) resource.cardName else from
        if (resource != null) entry.inheritedFromImage = resource.imageUrl
    }
}
