package com.drive.drive_manager.simulator.engine.effects.actions;

import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.engine.effects.model.Action.SetTapped;
import com.drive.drive_manager.simulator.engine.effects.model.Selector;
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler;
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext;
import com.drive.drive_manager.simulator.engine.effects.selection.Selection;

import java.util.ArrayList;
import java.util.List;

/** "agota" and "reactiva": the rules on who can be exhausted or reactivated are in the context. */
public final class SetTappedHandler implements ActionHandler<SetTapped> {

    private static List<CardSlot> candidates(EffectContext ctx, SetTapped action) {
        return Selection.candidates(ctx, action.cards(), false).stream()
                .filter(c -> ctx.onField(c) && !ctx.unaffected(c) && (action.tapped() ? ctx.canTap(c) : ctx.canUntap(c))).toList();
    }

    @Override
    public boolean possible(EffectContext ctx, SetTapped action) {
        if (action.cards().fromMemory()) return true;
        return candidates(ctx, action).size() >= Math.max(1, ctx.amount(action.cards().min()));
    }

    @Override
    public boolean run(EffectContext ctx, SetTapped action) {
        Selector selector = action.cards();
        List<CardSlot> valid = candidates(ctx, action);
        int max = Math.min(ctx.amount(selector.max()), valid.size());
        if (max <= 0) {
            ctx.done(false);
            return false;
        }
        if (selector.mode() != Selector.Mode.CHOOSE) {
            apply(ctx, action, valid.subList(0, max));
            return false;
        }
        int min = Math.min(ctx.amount(selector.min()), max);
        String verb = action.tapped() ? "Agota " : "Reactiva ";
        String filter = selector.filter().describe();
        ctx.ask(verb + (min < max ? "hasta " : "") + max + (max == 1 ? " carta" : " cartas") + (filter.isBlank() ? "" : " " + filter) + ".",
                action.tapped() ? "Agotar" : "Reactivar", Selection.pool(ctx, selector), valid, min, max);
        return true;
    }

    @Override
    public String answer(EffectContext ctx, SetTapped action, Answer answer) {
        List<CardSlot> valid = candidates(ctx, action);
        List<CardSlot> picked = new ArrayList<>();
        for (String id : answer.picks()) {
            CardSlot card = valid.stream().filter(c -> c.getInstanceId().equals(id)).findFirst().orElse(null);
            if (card == null) return "Esa carta ya no se puede " + (action.tapped() ? "agotar" : "reactivar");
            picked.add(card);
        }
        apply(ctx, action, picked);
        return null;
    }

    private static void apply(EffectContext ctx, SetTapped action, List<CardSlot> cards) {
        for (CardSlot card : new ArrayList<>(cards)) ctx.setTapped(card, action.tapped());
        ctx.done(!cards.isEmpty());
        ctx.counted(cards.size());
    }
}
