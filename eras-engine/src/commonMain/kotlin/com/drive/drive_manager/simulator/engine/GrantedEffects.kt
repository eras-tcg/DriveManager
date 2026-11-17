package com.drive.drive_manager.simulator.engine

import kotlin.jvm.JvmStatic
import com.drive.drive_manager.simulator.engine.effects.model.Action
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom

/**
 * Quoted abilities an effect gives a card for a while ("otro aliado gana hasta el final del turno: «En Destrucción …»", [EffectStep.GrantEffect]).
 * The card carries the ability as a gained keyword, its [marker] («text»), so it is shown, lasts and is lost as any ability gained until the end
 * of the turn; [CardEffects.of] adds the effect of each marker the card has. The effects come from the card files (the steps that grant them).
 */
object GrantedEffects {

    private val BY_MARKER: MutableMap<String, EffectScript> = HashMap()

    /** The keyword a card carries while it has the ability: the printed text between «». */
    @JvmStatic
    fun marker(effect: EffectScript): String = "«" + effect.text + "»"

    /** The effects a card's file grants (its steps and costs), so cards that gain them can run them. */
    @JvmStatic
    fun register(scripts: List<EffectScript>) {
        for (script in scripts) {
            for (step in (script.cost ?: frozenListOf()) + (script.steps ?: frozenListOf())) {
                if (step is EffectStep.GrantEffect && step.effect != null) BY_MARKER[marker(step.effect)] = step.effect
            }
        }
    }

    @JvmStatic
    fun byMarker(keyword: String): EffectScript? = BY_MARKER[keyword]

    @JvmStatic
    fun byId(id: String): EffectScript? = BY_MARKER.values.firstOrNull { it.id == id }

    /** The granted abilities the card has now (their markers), to keep with its destruction ("En Destrucción" still triggers). */
    @JvmStatic
    fun markersOf(card: CardSlot): List<String> = EffectEngine.keywords(card).filter { BY_MARKER.containsKey(it) }

    @JvmStatic
    fun grant(room: GameRoom, effect: PendingEffect, card: CardSlot, granted: EffectScript) {
        EngineContext(room, effect).grant(card, marker(granted), Action.Until.TURN)
    }
}
