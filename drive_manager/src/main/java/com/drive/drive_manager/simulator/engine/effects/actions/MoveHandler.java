package com.drive.drive_manager.simulator.engine.effects.actions;

import com.drive.drive_manager.simulator.engine.GameRandom;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.engine.effects.model.Action.Move;
import com.drive.drive_manager.simulator.engine.effects.model.Memory;
import com.drive.drive_manager.simulator.engine.effects.model.Selector;
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler;
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext;
import com.drive.drive_manager.simulator.engine.effects.selection.Selection;

import java.util.ArrayList;
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
        if (selector.waitsFor(Memory.SUBJECT) && ctx.saved(Memory.SUBJECT).isEmpty()) return true;
        if (selector.zones().equals("looked") || selector.fromMemory()) return true;
        List<CardSlot> found = candidates(ctx, action);
        if (selector.self() == Selector.Self.REQUIRE && found.stream().noneMatch(ctx::isSelf)) return false;
        return found.size() >= Math.max(1, ctx.amount(selector.min()));
    }

    @Override
    public boolean run(EffectContext ctx, Move action) {
        Selector selector = action.cards();
        if (Move.DECK_TOP_OR_BOTTOM.equals(action.to())) {
            if (ctx.looked().isEmpty()) return false;
            // One choice: their order and where they go (the first one of the order ends up highest)
            ctx.ask(ctx.looked().size() > 1 ? "Ordena las cartas (la de la izquierda queda más arriba) y elige si van al tope o al fondo de tu mazo."
                    : "Elige si la carta va al tope o al fondo de tu mazo.", null, ctx.looked(), ctx.looked(), 0, 1);
            ctx.asksTopOrBottom();
            return true;
        }
        List<CardSlot> valid = candidates(ctx, action);
        int max = Math.min(ctx.amount(selector.max()), valid.size());
        // With nothing to pick, the rest of the looked cards are still ordered
        if (action.rest() != null && !ctx.looked().isEmpty()) {
            int min = Math.min(ctx.amount(selector.min()), max);
            ctx.ask(instruction(ctx, action, min, max), action.label() != null ? action.label() : label(action.to()), ctx.looked(), valid, min, max);
            ctx.asksOrder(Move.DECK_TOP.equals(action.rest()));
            return true;
        }
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
        if (Move.DECK_TOP_OR_BOTTOM.equals(action.to())) {
            ctx.lookedRestTo(answer.picks().isEmpty() ? Move.DECK_BOTTOM : Move.DECK_TOP, answer.order());
            return null;
        }
        List<CardSlot> valid = candidates(ctx, action);
        List<CardSlot> picked = new ArrayList<>();
        for (String id : answer.picks()) {
            CardSlot card = valid.stream().filter(c -> c.getInstanceId().equals(id)).findFirst().orElse(null);
            if (card == null) return "Esa carta ya no está disponible";
            picked.add(card);
        }
        if (action.cards().self() == Selector.Self.REQUIRE && !picked.isEmpty() && picked.stream().noneMatch(ctx::isSelf)) return "Debes incluir esta carta";
        move(ctx, action, picked);
        if (action.rest() != null) ctx.lookedRestTo(action.rest(), answer.order());
        return null;
    }

    /** Never a card already where it is going. */
    private static List<CardSlot> candidates(EffectContext ctx, Move action) {
        return Selection.candidates(ctx, action.cards(), false).stream().filter(c -> !ctx.alreadyIn(c, action.to()) && !ctx.unaffected(c)).toList();
    }

    private static void move(EffectContext ctx, Move action, List<CardSlot> cards) {
        List<CardSlot> ordered = new ArrayList<>(cards);
        if (action.random()) GameRandom.shuffle(ordered, ctx.random());
        List<CardSlot> done = ctx.move(ordered, action.to(), action.exhausted(), action.faceDown());
        // "esta carta" is not what "si tributaste…" or "el material tributado" read
        if (action.cards().mode() == Selector.Mode.SELF) ctx.done(!done.isEmpty());
        else ctx.moved(done);
    }

    private static String label(String to) {
        return switch (to) {
            case Move.TRIBUTES -> "Tributar";
            case Move.DISCARD -> "Descartar";
            case Move.DECK_BOTTOM -> "Al fondo";
            case Move.DECK_TOP -> "Al tope";
            case Move.HAND -> "A tu mano";
            case Move.FRONT -> "A la línea frontal";
            case Move.BACK -> "A la retaguardia";
            case Move.LIFE_TOP -> "A tus vidas";
            default -> "Elegida";
        };
    }

    private static String instruction(EffectContext ctx, Move action, int min, int max) {
        String what = (min < max ? "hasta " : "") + max + " " + (max == 1 ? "carta" : "cartas");
        String filter = action.cards().filter().describe();
        String cards = what + (filter.isBlank() ? "" : " " + filter) + " de " + ctx.describeZones(action.cards().zones());
        if (action.cards().self() == Selector.Self.REQUIRE) cards += ", incluida esta carta";
        String random = action.random() ? " en orden aleatorio" : "";
        if (action.to().startsWith("materials:") || action.to().startsWith("resources:")) {
            return "Añade " + cards + " como " + (action.to().startsWith("materials:") ? "material." : "recurso.");
        }
        if (action.rest() != null) {
            String rest = Move.DECK_TOP.equals(action.rest()) ? " Ordena el resto: vuelve al tope de tu mazo (la de la izquierda queda arriba)."
                    : " Ordena el resto: irá al fondo de tu mazo en ese orden.";
            return instructionOf(action, cards, random) + rest;
        }
        return instructionOf(action, cards, random);
    }

    private static String instructionOf(Move action, String cards, String random) {
        return switch (action.to()) {
            case Move.TRIBUTES -> "Tributa " + cards + ".";
            case Move.DISCARD -> "Descarta " + cards + ".";
            case Move.DECK_BOTTOM -> "Envía " + cards + " al fondo de tu mazo" + random + ".";
            case Move.DECK_TOP -> "Coloca " + cards + " al tope de tu mazo.";
            case Move.HAND -> "Toma " + cards + " para tu mano.";
            case Move.FRONT -> "Coloca " + cards + " en tu línea frontal" + (action.exhausted() ? ", agotada." : ".");
            case Move.BACK -> "Coloca " + cards + " en tu retaguardia" + (action.exhausted() ? ", agotada." : ".");
            case Move.LIFE_TOP -> "Coloca " + cards + " al tope de tus vidas" + (action.faceDown() ? "." : ", bocarriba.");
            default -> "Elige " + cards + ".";
        };
    }
}
