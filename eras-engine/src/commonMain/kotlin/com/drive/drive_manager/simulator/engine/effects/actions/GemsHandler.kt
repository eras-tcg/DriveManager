package com.drive.drive_manager.simulator.engine.effects.actions

import com.drive.drive_manager.simulator.engine.effects.model.Action.Gems
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext

/** "gana N gemas"; a negative N is "paga N gemas", possible only when they can be paid. */
class GemsHandler : ActionHandler<Gems> {

    override fun possible(ctx: EffectContext, action: Gems): Boolean {
        val count = ctx.amount(action.count)
        return count >= 0 || ctx.canPayGems(-count)
    }

    override fun run(ctx: EffectContext, action: Gems): Boolean {
        ctx.gainGems(ctx.amount(action.count))
        return false
    }
}
