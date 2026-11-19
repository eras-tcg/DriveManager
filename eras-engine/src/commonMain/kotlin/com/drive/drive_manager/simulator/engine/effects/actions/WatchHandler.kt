package com.drive.drive_manager.simulator.engine.effects.actions

import com.drive.drive_manager.simulator.engine.effects.model.Action.Watch
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler.Answer
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext
import com.drive.drive_manager.simulator.engine.effects.selection.Selection

/** "el enemigo elegido": this card follows those cards, and the rest of its text comes when one of them leaves the field. */
class WatchHandler : ActionHandler<Watch> {

    override fun possible(ctx: EffectContext, action: Watch): Boolean {
        return true
    }

    override fun run(ctx: EffectContext, action: Watch): Boolean {
        val cards = Selection.candidates(ctx, action.cards!!, false).filter { ctx.onField(it) }
        ctx.watch(cards, action.effect, action.untilTurnEnd)
        return false
    }

    override fun answer(ctx: EffectContext, action: Watch, answer: Answer): String? {
        return "Este paso no espera ninguna elección"
    }
}
