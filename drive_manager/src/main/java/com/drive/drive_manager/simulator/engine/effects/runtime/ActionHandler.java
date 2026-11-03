package com.drive.drive_manager.simulator.engine.effects.runtime;

import com.drive.drive_manager.simulator.engine.effects.model.Action;

import java.util.List;

/** What one basic action does. One class per action, registered in {@link Actions}. */
public interface ActionHandler<A extends Action> {

    /** Whether it can be done now (a cost must be payable in full; the rest decide whether an optional effect is offered). */
    boolean possible(EffectContext ctx, A action);

    /** Does it. {@code true}: it asked the player and waits for {@link #answer}. */
    boolean run(EffectContext ctx, A action);

    /** The player's choice. Returns an error for the player, or null when accepted. */
    default String answer(EffectContext ctx, A action, Answer answer) {
        return "Esta acción no espera ninguna elección";
    }

    /** What the player answered: the cards chosen and, for choices that order the rest, their order. */
    record Answer(List<String> picks, List<String> order) {}
}
