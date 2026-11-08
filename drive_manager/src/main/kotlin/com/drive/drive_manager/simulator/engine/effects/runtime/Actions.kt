package com.drive.drive_manager.simulator.engine.effects.runtime

import com.drive.drive_manager.simulator.engine.frozenMapOfEntries
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
import kotlin.reflect.KClass

/** The handler of each action. A new action is a record in [Action], a class in effects/actions and a line here. */
object Actions {

    private val HANDLERS: Map<KClass<out Action>, ActionHandler<*>> = frozenMapOfEntries<KClass<out Action>, ActionHandler<*>>(
            Action.Select::class to SelectHandler(),
            Action.Attack::class to AttackHandler(),
            Action.AtEndOfTurn::class to AtEndOfTurnHandler(),
            Action.Move::class to MoveHandler(),
            Action.Draw::class to DrawHandler(),
            Action.Gems::class to GemsHandler(),
            Action.Damage::class to DamageHandler(),
            Action.Look::class to LookHandler(),
            Action.SetTapped::class to SetTappedHandler(),
            Action.Modify::class to ModifyHandler(),
            Action.Destroy::class to DestroyHandler(),
            Action.Summon::class to SummonHandler(),
            Action.Play::class to PlayHandler(),
            Action.Sequence::class to SequenceHandler(),
            Action.SummonSpecial::class to SummonSpecialHandler())

    @JvmStatic
    fun handled(): Set<KClass<out Action>> {
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
        return HANDLERS[action::class] as ActionHandler<A>
    }
}
