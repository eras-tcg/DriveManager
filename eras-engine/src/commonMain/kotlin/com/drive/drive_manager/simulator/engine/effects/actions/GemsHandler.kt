package com.drive.drive_manager.simulator.engine.effects.actions

import com.drive.drive_manager.simulator.engine.effects.model.Action.Gems
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext

/** "gana N gemas". */
class GemsHandler : ActionHandler<Gems> {

    override fun possible(ctx: EffectContext, action: Gems): Boolean {
        return true
    }

    override fun run(ctx: EffectContext, action: Gems): Boolean {
        ctx.gainGems(ctx.amount(action.count))
        return false
    }
}
