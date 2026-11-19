package com.drive.drive_manager.simulator.engine.effects.actions

import com.drive.drive_manager.simulator.engine.effects.model.Action.Look
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext

/**
 * "mira las primeras N cartas de tu mazo", or "excava cartas de tu mazo hasta que reveles N cartas o 1 [filtro]": they stay with the effect until
 * an action moves them (an excavation's rest goes to the bottom of the deck in random order: the card file says it with a Move).
 */
class LookHandler : ActionHandler<Look> {

    override fun possible(ctx: EffectContext, action: Look): Boolean {
        return true
    }

    override fun run(ctx: EffectContext, action: Look): Boolean {
        if (action.excavate || action.until != null) ctx.excavate(ctx.amount(action.count), action.until)
        else ctx.look(ctx.amount(action.count))
        return false
    }
}
