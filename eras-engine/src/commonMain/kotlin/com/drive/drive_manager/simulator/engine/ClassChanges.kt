package com.drive.drive_manager.simulator.engine

import kotlin.jvm.JvmStatic
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.FloatingEffect
import com.drive.drive_manager.simulator.room.GameRoom

/**
 * "cambia el tipo de 1 aliado [X] a [Y] hasta el final del turno": a floating change of a printed property. It is kept as a floating effect
 * of the card (shown with its details, gone at the end of the turn or when the card leaves the field) whose keyword is [MARK] + "X>Y", and
 * every filter reads the card's types through [classes]. A creature summoned from it does not get it (rule: a change of type of the
 * summoner stays with the card that left the field).
 */
object ClassChanges {

    /** The keyword of the floating effect that carries a change: "tipo:ángel>ángel caído". */
    const val MARK = "tipo:"

    @JvmStatic
    fun isChange(f: FloatingEffect): Boolean = f.keyword != null && f.keyword.startsWith(MARK)

    /** The card's types now: its printed ones, with the changes it has. The same type twice counts once (designer, Kristia). */
    @JvmStatic
    fun classes(card: CardSlot): List<String> {
        val own: List<String> = distinct(card.classes ?: frozenListOf())
        val changes = card.floating?.filter { isChange(it) } ?: return own
        if (changes.isEmpty()) return own
        val now = ArrayList(own)
        for (f in changes) {
            val parts = f.keyword!!.substring(MARK.length).split(">")
            if (parts.size != 2) continue
            if (now.removeAll { it.equals(parts[0], ignoreCase = true) } && now.none { it.equals(parts[1], ignoreCase = true) }) now.add(parts[1])
        }
        return now
    }

    private fun distinct(types: List<String>): List<String> {
        val seen = ArrayList<String>()
        for (t in types) if (seen.none { it.equals(t, ignoreCase = true) }) seen.add(t)
        return if (seen.size == types.size) types else seen
    }

    /** The card stops being `from` and is `to` until the end of the turn. Nothing when it is not `from` now. */
    @JvmStatic
    fun change(room: GameRoom, effect: PendingEffect, card: CardSlot, from: String, to: String): Boolean {
        if (classes(card).none { it.equals(from, ignoreCase = true) }) return false
        val text = "Su tipo [$from] pasa a ser [$to] hasta el final del turno"
        EffectEngine.floating(card, effect, text, "turn", 0, "$MARK$from>$to")
        EffectEngine.record(room, GameEvent.GAIN, effect.controllerId, card, null, null, effect, frozenMapOf<String, Any?>("classFrom", from, "classTo", to),
            EffectEngine.name(card) + ": " + text.lowercase().replaceFirstChar { it.uppercase() } + " (" + effect.sourceName + ")")
        return true
    }
}
