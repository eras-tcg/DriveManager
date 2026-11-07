package com.drive.drive_manager.simulator.engine.effects.runtime

import com.drive.drive_manager.simulator.engine.effects.model.Action

/** What one basic action does. One class per action, registered in [Actions]. */
interface ActionHandler<A : Action> {

    /** Whether it can be done now (a cost must be payable in full; the rest decide whether an optional effect is offered). */
    fun possible(ctx: EffectContext, action: A): Boolean

    /** Does it. `true`: it asked the player and waits for [answer]. */
    fun run(ctx: EffectContext, action: A): Boolean

    /** The player's choice. Returns an error for the player, or null when accepted. */
    fun answer(ctx: EffectContext, action: A, answer: Answer): String? {
        return "Esta acción no espera ninguna elección"
    }

    /** What the player answered: the cards chosen and, for choices that order the rest, their order. */
    @JvmRecord
    data class Answer(val picks: List<String?>?, val order: List<String?>?)
}
