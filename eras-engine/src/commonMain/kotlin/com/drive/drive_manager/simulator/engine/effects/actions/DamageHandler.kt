package com.drive.drive_manager.simulator.engine.effects.actions

import com.drive.drive_manager.simulator.engine.effects.model.Action.Damage
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext

/** "infringe N de daño directo" by effect. */
class DamageHandler : ActionHandler<Damage> {

    override fun possible(ctx: EffectContext, action: Damage): Boolean {
        return true
    }

    override fun run(ctx: EffectContext, action: Damage): Boolean {
        ctx.damage(ctx.amount(action.amount))
        return false
    }
}
