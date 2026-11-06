package com.drive.drive_manager.simulator.engine.effects.actions;

import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.engine.effects.model.Action.Destroy;
import com.drive.drive_manager.simulator.engine.effects.model.Selector;
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler;
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext;
import com.drive.drive_manager.simulator.engine.effects.selection.Selection;

import java.util.ArrayList;
import java.util.List;

/** "destruye" and "sacrifica" (rule 4.0.4.2: sacrificing is destroying and going to the Tributos). */
public final class DestroyHandler implements ActionHandler<Destroy> {

    private static List<CardSlot> candidates(EffectContext ctx, Destroy action) {
        return Selection.candidates(ctx, action.cards(), false).stream().filter(ctx::onField).toList();
    }

    @Override
    public boolean possible(EffectContext ctx, Destroy action) {
        if (action.cards().fromMemory()) return true;
        return candidates(ctx, action).size() >= Math.max(1, ctx.amount(action.cards().min()));
    }

    @Override
    public boolean run(EffectContext ctx, Destroy action) {
        Selector selector = action.cards();
        List<CardSlot> valid = candidates(ctx, action);
        int max = Math.min(ctx.amount(selector.max()), valid.size());
        if (max <= 0) {
            ctx.done(false);
            return false;
        }
        if (selector.mode() != Selector.Mode.CHOOSE) {
            apply(ctx, action, new ArrayList<>(valid.subList(0, max)));
            return false;
        }
        int min = Math.min(ctx.amount(selector.min()), max);
        String filter = selector.filter().describe();
        ctx.ask((action.sacrifice() ? "Sacrifica " : "Destruye ") + (min < max ? "hasta " : "") + max + (max == 1 ? " criatura" : " criaturas")
                        + (filter.isBlank() ? "" : " " + filter) + ".",
                action.sacrifice() ? "Sacrificar" : "Destruir", Selection.pool(ctx, selector), valid, min, max);
        return true;
    }

    @Override
    public String answer(EffectContext ctx, Destroy action, Answer answer) {
        List<CardSlot> valid = candidates(ctx, action);
        List<CardSlot> picked = new ArrayList<>();
        for (String id : answer.picks()) {
            CardSlot card = valid.stream().filter(c -> c.getInstanceId().equals(id)).findFirst().orElse(null);
            if (card == null) return "Esa criatura ya no está en el campo";
            picked.add(card);
        }
        apply(ctx, action, picked);
        return null;
    }

    private static void apply(EffectContext ctx, Destroy action, List<CardSlot> cards) {
        // Saved before: "de nivel menor que el aliado destruido" reads them once they are gone
        ctx.moved(cards);
        for (CardSlot card : cards) ctx.destroy(card, action.sacrifice());
    }
}
