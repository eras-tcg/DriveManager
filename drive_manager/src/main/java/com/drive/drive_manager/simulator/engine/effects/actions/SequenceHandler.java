package com.drive.drive_manager.simulator.engine.effects.actions;

import com.drive.drive_manager.simulator.engine.effects.model.Action;
import com.drive.drive_manager.simulator.engine.effects.model.Action.Sequence;
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler;
import com.drive.drive_manager.simulator.engine.effects.runtime.Actions;
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext;

/** Several actions that are one instruction of a card: each one runs after the player answers the one before. */
public final class SequenceHandler implements ActionHandler<Sequence> {

    @Override
    public boolean possible(EffectContext ctx, Sequence action) {
        return Actions.possible(ctx, action.actions().get(0));
    }

    @Override
    public boolean run(EffectContext ctx, Sequence action) {
        for (int i = ctx.sequenceIndex(); i < action.actions().size(); i++) {
            ctx.sequenceIndex(i);
            if (Actions.run(ctx, action.actions().get(i))) return true;
        }
        ctx.sequenceIndex(0);
        return false;
    }

    @Override
    public String answer(EffectContext ctx, Sequence action, Answer answer) {
        Action current = action.actions().get(ctx.sequenceIndex());
        String error = Actions.answer(ctx, current, answer);
        if (error != null) return error;
        int next = ctx.sequenceIndex() + 1;
        if (next >= action.actions().size()) {
            ctx.sequenceIndex(0);
            return null;
        }
        ctx.sequenceIndex(next);
        ctx.goOn();
        return null;
    }
}
