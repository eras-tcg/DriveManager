package com.drive.drive_manager.simulator.engine.effects.actions;

import com.drive.drive_manager.simulator.engine.effects.model.Action.AtEndOfTurn;
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler;
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext;

/** "al final del turno: …". */
public final class AtEndOfTurnHandler implements ActionHandler<AtEndOfTurn> {

    @Override
    public boolean possible(EffectContext ctx, AtEndOfTurn action) {
        return true;
    }

    @Override
    public boolean run(EffectContext ctx, AtEndOfTurn action) {
        ctx.atEndOfTurn(action.effect());
        return false;
    }

    @Override
    public String answer(EffectContext ctx, AtEndOfTurn action, Answer answer) {
        return "Este paso no espera ninguna elección";
    }
}
