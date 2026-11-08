package com.drive.drive_manager.simulator.engine.effects.actions

import com.drive.drive_manager.simulator.engine.effects.model.Action.Draw
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext

/** "roba N cartas": fewer if the deck is shorter. */
class DrawHandler : ActionHandler<Draw> {

    override fun possible(ctx: EffectContext, action: Draw): Boolean {
        return true
    }

    override fun run(ctx: EffectContext, action: Draw): Boolean {
        ctx.draw(ctx.amount(action.count), action.bothPlayers)
        return false
    }
}
