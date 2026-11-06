package com.drive.drive_manager.simulator.engine.effects.actions;

import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.engine.effects.model.Action.Summon;
import com.drive.drive_manager.simulator.engine.effects.model.Selector;
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler;
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext;
import com.drive.drive_manager.simulator.engine.effects.selection.Selection;

import java.util.ArrayList;
import java.util.List;

/** "invoca": a summon by effect (rule 9.1.0.2): pays the cost, enters the Retaguardia unless it says otherwise, then the regulatory draw. */
public final class SummonHandler implements ActionHandler<Summon> {

    private static List<CardSlot> candidates(EffectContext ctx, Summon action) {
        int reduce = ctx.amount(action.reduce());
        return Selection.candidates(ctx, action.cards(), false).stream()
                .filter(c -> "creature".equals(c.getCardType()) && c.getCost() != null && ctx.canPay(Math.max(0, c.getCost() - reduce)))
                // An effect does not act on its own card, unless it says "esta carta" or comes back later
                .filter(c -> action.cards().mode() == Selector.Mode.SELF || !ctx.isSelf(c) || ctx.delayed())
                .toList();
    }

    @Override
    public boolean possible(EffectContext ctx, Summon action) {
        return candidates(ctx, action).size() >= Math.max(1, ctx.amount(action.cards().min()));
    }

    @Override
    public boolean run(EffectContext ctx, Summon action) {
        Selector selector = action.cards();
        List<CardSlot> valid = candidates(ctx, action);
        if (valid.isEmpty()) {
            ctx.note("No hay criaturas que este efecto pueda invocar");
            return false;
        }
        int max = Math.min(ctx.amount(selector.max()), valid.size());
        if (selector.mode() != Selector.Mode.CHOOSE) {
            apply(ctx, action, new ArrayList<>(valid.subList(0, max)));
            return false;
        }
        int reduce = ctx.amount(action.reduce());
        int min = Math.min(ctx.amount(selector.min()), max);
        String filter = selector.filter().describe();
        String cost = reduce >= Summon.FREE ? " sin pagar su coste" : reduce > 0 ? " reduciendo su coste en " + reduce : " pagando su coste";
        ctx.ask("Invoca " + (min < max ? "hasta " : "") + max + (max == 1 ? " criatura" : " criaturas") + (filter.isBlank() ? "" : " " + filter)
                + " de " + ctx.describeZones(selector.zones()) + cost + ". Entra en " + (action.toFront() ? "tu línea frontal" : "Retaguardia") + " y robas 1 carta.", "Invocar", Selection.pool(ctx, selector), valid, min, max);
        return true;
    }

    @Override
    public String answer(EffectContext ctx, Summon action, Answer answer) {
        List<CardSlot> valid = candidates(ctx, action);
        List<CardSlot> picked = new ArrayList<>();
        for (String id : answer.picks()) {
            CardSlot card = valid.stream().filter(c -> c.getInstanceId().equals(id)).findFirst().orElse(null);
            if (card == null) return "Ya no puedes invocar esa carta";
            picked.add(card);
        }
        int reduce = ctx.amount(action.reduce());
        int total = picked.stream().mapToInt(c -> Math.max(0, (c.getCost() == null ? 0 : c.getCost()) - reduce)).sum();
        if (!ctx.canPay(total)) return "No tienes gemas para pagarlas todas (" + total + ")";
        apply(ctx, action, picked);
        return null;
    }

    private static void apply(EffectContext ctx, Summon action, List<CardSlot> cards) {
        int reduce = ctx.amount(action.reduce());
        for (CardSlot card : cards) ctx.summon(card, reduce, action.toFront());
        ctx.done(!cards.isEmpty());
    }
}
