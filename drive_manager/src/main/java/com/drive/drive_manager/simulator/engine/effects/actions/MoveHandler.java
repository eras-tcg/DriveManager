package com.drive.drive_manager.simulator.engine.effects.actions;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.engine.effects.model.Action.Move;
import com.drive.drive_manager.simulator.engine.effects.model.Memory;
import com.drive.drive_manager.simulator.engine.effects.model.Selector;
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler;
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext;
import com.drive.drive_manager.simulator.engine.effects.selection.Selection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Cards go to a zone. Picking them is not a target: an effect that says "elige" uses Select first. A cost needs the whole
 * amount ({@code possible}); at resolution as many as there are move (designer ruling 2026-11-01).
 */
public final class MoveHandler implements ActionHandler<Move> {

    @Override
    public boolean possible(EffectContext ctx, Move action) {
        Selector selector = action.cards();
        // Its cards depend on the ally chosen by an action before it, which has not been chosen yet
        if (selector.zones().contains(":subject") && ctx.saved(Memory.SUBJECT).isEmpty()) return true;
        List<CardSlot> found = candidates(ctx, action);
        if (selector.self() == Selector.Self.REQUIRE && found.stream().noneMatch(ctx::isSelf)) return false;
        return found.size() >= Math.max(1, ctx.amount(selector.min()));
    }

    @Override
    public boolean run(EffectContext ctx, Move action) {
        Selector selector = action.cards();
        List<CardSlot> valid = candidates(ctx, action);
        int max = Math.min(ctx.amount(selector.max()), valid.size());
        if (max <= 0) {
            ctx.moved(List.of());
            return false;
        }
        if (selector.mode() != Selector.Mode.CHOOSE) {
            move(ctx, action, valid.subList(0, max));
            return false;
        }
        int min = Math.min(ctx.amount(selector.min()), max);
        ctx.ask(instruction(ctx, action, min, max), action.label() != null ? action.label() : label(action.to()), Selection.pool(ctx, selector), valid, min, max);
        if (selector.self() == Selector.Self.REQUIRE) valid.stream().filter(ctx::isSelf).findFirst().ifPresent(ctx::requireInChoice);
        return true;
    }

    @Override
    public String answer(EffectContext ctx, Move action, Answer answer) {
        List<CardSlot> valid = candidates(ctx, action);
        List<CardSlot> picked = new ArrayList<>();
        for (String id : answer.picks()) {
            CardSlot card = valid.stream().filter(c -> c.getInstanceId().equals(id)).findFirst().orElse(null);
            if (card == null) return "Esa carta ya no está disponible";
            picked.add(card);
        }
        if (action.cards().self() == Selector.Self.REQUIRE && !picked.isEmpty() && picked.stream().noneMatch(ctx::isSelf)) return "Debes incluir esta carta";
        move(ctx, action, picked);
        return null;
    }

    /** Never a card already where it is going. */
    private static List<CardSlot> candidates(EffectContext ctx, Move action) {
        return Selection.candidates(ctx, action.cards(), false).stream().filter(c -> !ctx.alreadyIn(c, action.to())).toList();
    }

    private static void move(EffectContext ctx, Move action, List<CardSlot> cards) {
        List<CardSlot> ordered = new ArrayList<>(cards);
        if (action.random()) Collections.shuffle(ordered);
        ctx.move(ordered, action.to());
        ctx.moved(ordered);
    }

    private static String label(String to) {
        return switch (to) {
            case Move.TRIBUTES -> "Tributar";
            case Move.DISCARD -> "Descartar";
            case Move.DECK_BOTTOM -> "Al fondo";
            case Move.DECK_TOP -> "Al tope";
            case Move.HAND -> "A tu mano";
            default -> "Elegida";
        };
    }

    private static String instruction(EffectContext ctx, Move action, int min, int max) {
        String what = (min < max ? "hasta " : "") + max + " " + (max == 1 ? "carta" : "cartas");
        String filter = action.cards().filter().describe();
        String cards = what + (filter.isBlank() ? "" : " " + filter) + " de " + ctx.describeZones(action.cards().zones());
        if (action.cards().self() == Selector.Self.REQUIRE) cards += ", incluida esta carta";
        String random = action.random() ? " en orden aleatorio" : "";
        return switch (action.to()) {
            case Move.TRIBUTES -> "Tributa " + cards + ".";
            case Move.DISCARD -> "Descarta " + cards + ".";
            case Move.DECK_BOTTOM -> "Envía " + cards + " al fondo de tu mazo" + random + ".";
            case Move.DECK_TOP -> "Coloca " + cards + " al tope de tu mazo.";
            case Move.HAND -> "Toma " + cards + " para tu mano.";
            default -> "Elige " + cards + ".";
        };
    }
}
