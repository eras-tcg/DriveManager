package com.drive.drive_manager.simulator.engine.effects.actions

import com.drive.drive_manager.simulator.engine.effects.model.Action.Look
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext

/** "mira las primeras N cartas de tu mazo": they stay with the effect until an action moves them. */
class LookHandler : ActionHandler<Look> {

    override fun possible(ctx: EffectContext, action: Look): Boolean {
        return true
    }

    override fun run(ctx: EffectContext, action: Look): Boolean {
        ctx.look(ctx.amount(action.count))
        return false
    }
}
