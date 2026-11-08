package com.drive.drive_manager.simulator.engine.effects.actions

import com.drive.drive_manager.simulator.engine.effects.model.Action.SummonSpecial
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext

/** The special summon itself, with the creature, summoner and materials already chosen (rule 9). */
class SummonSpecialHandler : ActionHandler<SummonSpecial> {

    override fun possible(ctx: EffectContext, action: SummonSpecial): Boolean {
        return true
    }

    override fun run(ctx: EffectContext, action: SummonSpecial): Boolean {
        ctx.summonSpecial(action.rule)
        return false
    }
}
