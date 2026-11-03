package com.drive.drive_manager.simulator.engine.effects.actions;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.engine.GameEvent;
import com.drive.drive_manager.simulator.engine.effects.model.Action.Select;
import com.drive.drive_manager.simulator.engine.effects.model.Memory;
import com.drive.drive_manager.simulator.engine.effects.model.Selector;
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler;
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext;
import com.drive.drive_manager.simulator.engine.effects.selection.Selection;

import java.util.List;
import java.util.Map;

/**
 * The player picks cards. {@code possible} asks for the whole amount, which is what a cost needs; at resolution it asks for as
 * many as there are (designer ruling 2026-11-01).
 */
public final class SelectHandler implements ActionHandler<Select> {

    @Override
    public boolean possible(EffectContext ctx, Select action) {
        Selector selector = action.selector();
        if (selector.special() != null) return !Selection.candidates(ctx, selector, false).isEmpty();
        // It depends on the ally chosen by an action before it, not chosen yet
        if (selector.waitsFor(Memory.SUBJECT) && ctx.saved(Memory.SUBJECT).isEmpty()) return true;
        List<CardSlot> found = Selection.candidates(ctx, selector, action.target());
        if (selector.self() == Selector.Self.REQUIRE && found.stream().noneMatch(ctx::isSelf)) return false;
        return found.size() >= ctx.amount(selector.min());
    }

    @Override
    public boolean run(EffectContext ctx, Select action) {
        Selector selector = action.selector();
        List<CardSlot> valid = Selection.candidates(ctx, selector, action.target());
        if (valid.isEmpty()) {
            if (action.endsIfNone()) {
                ctx.note("No hay aliados que puedan elegirse");
                ctx.endEffect();
            }
            return false;
        }
        // "elige todos los enemigos": no choice, but they are targets
        if (selector.mode() == Selector.Mode.ALL) {
            answer(ctx, action, new Answer(valid.stream().map(CardSlot::getInstanceId).toList(), List.of()));
            return false;
        }
        if (selector.special() != null) {
            int[] bounds = ctx.specialBounds(selector.special(), valid.size());
            ctx.ask(ctx.specialInstruction(selector.special()), action.label(), Selection.pool(ctx, selector), valid, bounds[0], bounds[1]);
            if (bounds[2] > 0) ctx.limitLevelSum(bounds[2]);
            return true;
        }
        int max = Math.min(ctx.amount(selector.max()), valid.size());
        int min = Math.min(ctx.amount(selector.min()), max);
        String instruction = (action.target() ? "Elige " : "Selecciona ") + (min < max ? "hasta " : "") + max + " " + ctx.describe(selector)
                + (selector.self() == Selector.Self.REQUIRE ? ", incluida esta carta" : "") + ".";
        String label = action.label() != null ? action.label() : action.target() ? "Objetivo" : "Elegida";
        ctx.ask(instruction, label, Selection.pool(ctx, selector), valid, min, max);
        return true;
    }

    @Override
    public String answer(EffectContext ctx, Select action, Answer answer) {
        List<String> picks = answer.picks();
        List<CardSlot> valid = Selection.candidates(ctx, action.selector(), action.target());
        for (String id : picks) {
            if (valid.stream().noneMatch(c -> c.getInstanceId().equals(id))) return "Esa carta ya no se puede elegir";
        }
        if (action.selector().self() == Selector.Self.REQUIRE && !picks.isEmpty()
                && valid.stream().noneMatch(c -> ctx.isSelf(c) && picks.contains(c.getInstanceId()))) {
            return "Debes incluir esta carta";
        }
        if (action.selector().special() != null) {
            String wrong = ctx.specialCheck(action.selector().special(), picks);
            if (wrong != null) return wrong;
        }
        String saveAs = action.saveAs() != null ? action.saveAs() : action.target() ? Memory.TARGETS : Memory.LAST;
        ctx.save(saveAs, picks);
        ctx.save(Memory.ACTIVE_ALLIES, valid.stream().filter(c -> picks.contains(c.getInstanceId()) && !ctx.isOpponents(c) && !c.isTapped()).map(CardSlot::getInstanceId).toList());
        ctx.done(!picks.isEmpty());
        if (action.target() && !picks.isEmpty()) {
            List<String> names = picks.stream().map(id -> ctx.name(ctx.find(id))).toList();
            ctx.event(GameEvent.TARGET, ctx.find(picks.get(0)), Map.of("targetIds", picks),
                    ctx.player().getUsername() + " elige como objetivo a " + String.join(", ", names));
        }
        return null;
    }
}
