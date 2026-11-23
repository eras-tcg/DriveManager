package com.drive.drive_manager.simulator.engine

import kotlin.jvm.JvmStatic
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.FloatingEffect
import com.drive.drive_manager.simulator.room.GameRoom

/**
 * "puedes cambiar el nombre original de esta carta por el de …": a change of a printed property, kept as a floating effect of the card
 * (shown with its details) whose keyword is [MARK] + the new name. It lasts while the card stays on the field (gone when it leaves, and
 * not handed to a creature summoned from it, as a change of type). Every rule that compares names reads [name]: the card answers to the
 * new name only, but its own effects stay its own (they are found by the printed name).
 */
object NameChanges {

    /** The keyword of the floating effect that carries a change: "nombre:Zombi Errante". */
    const val MARK = "nombre:"

    @JvmStatic
    fun isChange(f: FloatingEffect): Boolean = f.keyword != null && f.keyword.startsWith(MARK)

    /** The card's name now: the last change it has, or its printed name. */
    @JvmStatic
    fun name(card: CardSlot): String? {
        val floating = card.floating ?: return card.cardName
        for (i in floating.indices.reversed()) {
            val f = floating[i]
            if (isChange(f)) return f.keyword!!.substring(MARK.length)
        }
        return card.cardName
    }

    /** The card is called `name` from now on, while it stays on the field. */
    @JvmStatic
    fun change(room: GameRoom, effect: PendingEffect, card: CardSlot, name: String) {
        val text = "Su nombre pasa a ser \"$name\""
        EffectEngine.floating(card, effect, text, Watches.WHILE_ON_FIELD, 0, "$MARK$name")
        EffectEngine.record(room, GameEvent.GAIN, effect.controllerId, card, null, null, effect, frozenMapOf<String, Any?>("name", name),
            EffectEngine.name(card) + ": " + text + " (" + effect.sourceName + ")")
    }
}
