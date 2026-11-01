package com.drive.drive_manager.simulator.engine.effects.actions;

import com.drive.drive_manager.simulator.engine.effects.model.Action.SummonSpecial;
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler;
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext;

/** The special summon itself, with the creature, summoner and materials already chosen (rule 9). */
public final class SummonSpecialHandler implements ActionHandler<SummonSpecial> {

    @Override
    public boolean possible(EffectContext ctx, SummonSpecial action) {
        return true;
    }

    @Override
    public boolean run(EffectContext ctx, SummonSpecial action) {
        ctx.summonSpecial(action.rule());
        return false;
    }
}
