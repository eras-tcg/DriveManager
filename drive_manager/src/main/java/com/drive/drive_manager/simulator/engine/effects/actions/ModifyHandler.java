package com.drive.drive_manager.simulator.engine.effects.actions;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.engine.effects.model.Action.Modify;
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler;
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext;
import com.drive.drive_manager.simulator.engine.effects.selection.Selection;

import java.util.ArrayList;

/** "gana fuerza N", "reduce su fuerza en N", "gana [habilidad]", "no puede …", for a while. A strength reduced to 0 destroys the creature. */
public final class ModifyHandler implements ActionHandler<Modify> {

    @Override
    public boolean possible(EffectContext ctx, Modify action) {
        return true;
    }

    @Override
    public boolean run(EffectContext ctx, Modify action) {
        for (CardSlot card : new ArrayList<>(Selection.candidates(ctx, action.cards(), false))) {
            if (!ctx.onField(card) || ctx.unaffected(card)) continue;
            if (action.toZero()) ctx.changeStrength(card, -ctx.strength(card), action.until());
            else if (action.strength() != null) ctx.changeStrength(card, ctx.amount(action.strength()), action.until());
            if (action.keyword() != null) ctx.grant(card, action.keyword(), action.until());
        }
        return false;
    }
}
