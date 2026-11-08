package com.drive.drive_manager.simulator.engine.effects.actions

import com.drive.drive_manager.simulator.engine.effects.model.Action.Attack
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler.Answer
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext

/** "declara ataque": the rules of the attack are in CombatRules. */
class AttackHandler : ActionHandler<Attack> {

    override fun possible(ctx: EffectContext, action: Attack): Boolean {
        return true
    }

    override fun run(ctx: EffectContext, action: Attack): Boolean {
        return ctx.attack(action.opponentOnly)
    }

    override fun answer(ctx: EffectContext, action: Attack, answer: Answer): String? {
        ctx.attackChosen(answer.picks)
        return null
    }
}
