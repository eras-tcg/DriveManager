package com.drive.drive_manager.simulator.engine.effects.actions;

import com.drive.drive_manager.simulator.engine.effects.model.Action.Look;
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler;
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext;

/** "mira las primeras N cartas de tu mazo": they stay with the effect until an action moves them. */
public final class LookHandler implements ActionHandler<Look> {

    @Override
    public boolean possible(EffectContext ctx, Look action) {
        return true;
    }

    @Override
    public boolean run(EffectContext ctx, Look action) {
        ctx.look(ctx.amount(action.count()));
        return false;
    }
}
