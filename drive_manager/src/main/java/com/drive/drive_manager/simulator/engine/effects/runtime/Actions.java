package com.drive.drive_manager.simulator.engine.effects.runtime;

import com.drive.drive_manager.simulator.engine.effects.actions.DamageHandler;
import com.drive.drive_manager.simulator.engine.effects.actions.DestroyHandler;
import com.drive.drive_manager.simulator.engine.effects.actions.DrawHandler;
import com.drive.drive_manager.simulator.engine.effects.actions.GemsHandler;
import com.drive.drive_manager.simulator.engine.effects.actions.LookHandler;
import com.drive.drive_manager.simulator.engine.effects.actions.ModifyHandler;
import com.drive.drive_manager.simulator.engine.effects.actions.MoveHandler;
import com.drive.drive_manager.simulator.engine.effects.actions.PlayHandler;
import com.drive.drive_manager.simulator.engine.effects.actions.SequenceHandler;
import com.drive.drive_manager.simulator.engine.effects.actions.SetTappedHandler;
import com.drive.drive_manager.simulator.engine.effects.actions.SummonSpecialHandler;
import com.drive.drive_manager.simulator.engine.effects.actions.SummonHandler;
import com.drive.drive_manager.simulator.engine.effects.actions.SelectHandler;
import com.drive.drive_manager.simulator.engine.effects.model.Action;

import java.util.Map;
import java.util.Set;

/** The handler of each action. A new action is a record in {@link Action}, a class in effects/actions and a line here. */
public final class Actions {

    private static final Map<Class<? extends Action>, ActionHandler<?>> HANDLERS = Map.ofEntries(
            Map.entry(Action.Select.class, new SelectHandler()),
            Map.entry(Action.Move.class, new MoveHandler()),
            Map.entry(Action.Draw.class, new DrawHandler()),
            Map.entry(Action.Gems.class, new GemsHandler()),
            Map.entry(Action.Damage.class, new DamageHandler()),
            Map.entry(Action.Look.class, new LookHandler()),
            Map.entry(Action.SetTapped.class, new SetTappedHandler()),
            Map.entry(Action.Modify.class, new ModifyHandler()),
            Map.entry(Action.Destroy.class, new DestroyHandler()),
            Map.entry(Action.Summon.class, new SummonHandler()),
            Map.entry(Action.Play.class, new PlayHandler()),
            Map.entry(Action.Sequence.class, new SequenceHandler()),
            Map.entry(Action.SummonSpecial.class, new SummonSpecialHandler()));

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
