package com.drive.drive_manager.simulator.engine.effects.runtime

import com.drive.drive_manager.simulator.engine.effects.actions.AtEndOfTurnHandler
import com.drive.drive_manager.simulator.engine.effects.actions.AttackHandler
import com.drive.drive_manager.simulator.engine.effects.actions.DamageHandler
import com.drive.drive_manager.simulator.engine.effects.actions.DestroyHandler
import com.drive.drive_manager.simulator.engine.effects.actions.DrawHandler
import com.drive.drive_manager.simulator.engine.effects.actions.GemsHandler
import com.drive.drive_manager.simulator.engine.effects.actions.LookHandler
import com.drive.drive_manager.simulator.engine.effects.actions.ModifyHandler
import com.drive.drive_manager.simulator.engine.effects.actions.MoveHandler
import com.drive.drive_manager.simulator.engine.effects.actions.PlayHandler
import com.drive.drive_manager.simulator.engine.effects.actions.SelectHandler
import com.drive.drive_manager.simulator.engine.effects.actions.SequenceHandler
import com.drive.drive_manager.simulator.engine.effects.actions.SetTappedHandler
import com.drive.drive_manager.simulator.engine.effects.actions.SummonHandler
import com.drive.drive_manager.simulator.engine.effects.actions.SummonSpecialHandler
import com.drive.drive_manager.simulator.engine.effects.model.Action

/** The handler of each action. A new action is a record in [Action], a class in effects/actions and a line here. */
object Actions {

    private val HANDLERS: Map<Class<out Action>, ActionHandler<*>> = java.util.Map.ofEntries<Class<out Action>, ActionHandler<*>>(
            java.util.Map.entry(Action.Select::class.java, SelectHandler()),
            java.util.Map.entry(Action.Attack::class.java, AttackHandler()),
            java.util.Map.entry(Action.AtEndOfTurn::class.java, AtEndOfTurnHandler()),
            java.util.Map.entry(Action.Move::class.java, MoveHandler()),
            java.util.Map.entry(Action.Draw::class.java, DrawHandler()),
            java.util.Map.entry(Action.Gems::class.java, GemsHandler()),
            java.util.Map.entry(Action.Damage::class.java, DamageHandler()),
            java.util.Map.entry(Action.Look::class.java, LookHandler()),
            java.util.Map.entry(Action.SetTapped::class.java, SetTappedHandler()),
            java.util.Map.entry(Action.Modify::class.java, ModifyHandler()),
            java.util.Map.entry(Action.Destroy::class.java, DestroyHandler()),
            java.util.Map.entry(Action.Summon::class.java, SummonHandler()),
            java.util.Map.entry(Action.Play::class.java, PlayHandler()),
            java.util.Map.entry(Action.Sequence::class.java, SequenceHandler()),
            java.util.Map.entry(Action.SummonSpecial::class.java, SummonSpecialHandler()))

    @JvmStatic
    fun handled(): Set<Class<out Action>> {
        return HANDLERS.keys
    }

    @JvmStatic
    fun possible(ctx: EffectContext, action: Action): Boolean {
        return handler(action).possible(ctx, action)
    }

    @JvmStatic
    fun run(ctx: EffectContext, action: Action): Boolean {
        return handler(action).run(ctx, action)
    }

    @JvmStatic
    fun answer(ctx: EffectContext, action: Action, answer: ActionHandler.Answer): String? {
        return handler(action).answer(ctx, action, answer)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <A : Action> handler(action: A): ActionHandler<A> {
        return HANDLERS[action.javaClass] as ActionHandler<A>
    }
}
