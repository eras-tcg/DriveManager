package com.drive.drive_manager.simulator.engine.effects.actions;

import com.drive.drive_manager.simulator.engine.effects.model.Action.Damage;
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler;
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext;

/** "infringe N de daño directo" by effect. */
public final class DamageHandler implements ActionHandler<Damage> {

    @Override
    public boolean possible(EffectContext ctx, Damage action) {
        return true;
    }

    @Override
    public boolean run(EffectContext ctx, Damage action) {
        ctx.damage(ctx.amount(action.amount()));
        return false;
    }
}
