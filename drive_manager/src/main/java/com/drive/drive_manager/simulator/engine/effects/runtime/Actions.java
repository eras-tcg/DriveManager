package com.drive.drive_manager.simulator.engine.effects.runtime;

import com.drive.drive_manager.simulator.engine.effects.actions.SelectHandler;
import com.drive.drive_manager.simulator.engine.effects.model.Action;

import java.util.Map;
import java.util.Set;

/** The handler of each action. A new action is a record in {@link Action}, a class in effects/actions and a line here. */
public final class Actions {

    private static final Map<Class<? extends Action>, ActionHandler<?>> HANDLERS = Map.of(
            Action.Select.class, new SelectHandler());

    private Actions() {}

    public static Set<Class<? extends Action>> handled() {
        return HANDLERS.keySet();
    }

    public static boolean possible(EffectContext ctx, Action action) {
        return handler(action).possible(ctx, action);
    }

    public static boolean run(EffectContext ctx, Action action) {
        return handler(action).run(ctx, action);
    }

    public static String answer(EffectContext ctx, Action action, ActionHandler.Answer answer) {
        return handler(action).answer(ctx, action, answer);
    }

    @SuppressWarnings("unchecked")
    private static <A extends Action> ActionHandler<A> handler(A action) {
        return (ActionHandler<A>) HANDLERS.get(action.getClass());
    }
}
