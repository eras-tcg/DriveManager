package com.drive.drive_manager.simulator.engine.effects.actions;

import com.drive.drive_manager.simulator.engine.effects.model.Action.Draw;
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler;
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext;

/** "roba N cartas": fewer if the deck is shorter. */
public final class DrawHandler implements ActionHandler<Draw> {

    @Override
    public boolean possible(EffectContext ctx, Draw action) {
        return true;
    }

    @Override
    public boolean run(EffectContext ctx, Draw action) {
        ctx.draw(ctx.amount(action.count()), action.bothPlayers());
        return false;
    }
}
