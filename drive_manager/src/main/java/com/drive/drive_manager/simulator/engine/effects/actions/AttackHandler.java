package com.drive.drive_manager.simulator.engine.effects.actions;

import com.drive.drive_manager.simulator.engine.effects.model.Action.Attack;
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler;
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext;

/** "declara ataque": the rules of the attack are in CombatRules. */
public final class AttackHandler implements ActionHandler<Attack> {

    @Override
    public boolean possible(EffectContext ctx, Attack action) {
        return true;
    }

    @Override
    public boolean run(EffectContext ctx, Attack action) {
        return ctx.attack(action.opponentOnly());
    }

    @Override
    public String answer(EffectContext ctx, Attack action, Answer answer) {
        ctx.attackChosen(answer.picks());
        return null;
    }
}
