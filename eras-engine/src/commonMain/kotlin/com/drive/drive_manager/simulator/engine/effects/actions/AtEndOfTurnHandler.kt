package com.drive.drive_manager.simulator.engine.effects.actions

import com.drive.drive_manager.simulator.engine.effects.model.Action.AtEndOfTurn
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler.Answer
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext

/** "al final del turno: …". */
class AtEndOfTurnHandler : ActionHandler<AtEndOfTurn> {

    override fun possible(ctx: EffectContext, action: AtEndOfTurn): Boolean {
        return true
    }

    override fun run(ctx: EffectContext, action: AtEndOfTurn): Boolean {
        ctx.atEndOfTurn(action.effect)
        return false
    }

    override fun answer(ctx: EffectContext, action: AtEndOfTurn, answer: Answer): String? {
        return "Este paso no espera ninguna elección"
    }
}
