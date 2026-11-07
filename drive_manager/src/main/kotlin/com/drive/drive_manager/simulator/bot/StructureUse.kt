package com.drive.drive_manager.simulator.bot

import com.drive.drive_manager.simulator.engine.GameEvent
import com.drive.drive_manager.simulator.room.GameRoom

/**
 * sUse (the user's call, 2026-11-05): what a structure is worth is what it yields. The starter decks' structures are
 * engines ("si pasa X: puedes agotar esta carta para ganar 1 gema y robar / tomar 1 carta"), and the table's score
 * barely sees a yield (gems count nothing, a card drawn for one tributed is still a card), so the bot left them
 * unused and played them without caring for what makes them fire. Each activation of an effect of a structure of its
 * own in a try is worth sUse: playing the structure before what fires it, and saying yes to it, pay.
 */
internal object StructureUse {

    /** The activations of its own structures' effects in the room's timeline since `from`. */
    @JvmStatic
    fun count(room: GameRoom, me: String?, from: Int): Int {
        val tl = room.timeline
        val self = room.getPlayer(me) ?: return 0
        var n = 0
        for (i in Math.max(0, from) until tl.size) {
            val e = tl[i]
            if (GameEvent.EFFECT_ACTIVATED != e.type || me!! != e.playerId || e.data == null) continue
            val source = e.data!!["sourceId"] ?: continue
            val id = source.toString()
            if (self.field.any { c -> id == c.instanceId && "structure" == c.cardType }) n++
        }
        return n
    }
}
