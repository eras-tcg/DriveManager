package com.drive.drive_manager.simulator.engine.effects.actions

import com.drive.drive_manager.simulator.engine.effects.model.Action.Sequence
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler.Answer
import com.drive.drive_manager.simulator.engine.effects.runtime.Actions
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext

/** Several actions that are one instruction of a card: each one runs after the player answers the one before. */
class SequenceHandler : ActionHandler<Sequence> {

    override fun possible(ctx: EffectContext, action: Sequence): Boolean {
        return Actions.possible(ctx, action.actions!![0])
    }

    override fun run(ctx: EffectContext, action: Sequence): Boolean {
        var i = ctx.sequenceIndex()
        while (i < action.actions!!.size) {
            ctx.sequenceIndex(i)
            if (Actions.run(ctx, action.actions[i])) return true
            i++
        }
        ctx.sequenceIndex(0)
        return false
    }

    override fun answer(ctx: EffectContext, action: Sequence, answer: Answer): String? {
        val current = action.actions!![ctx.sequenceIndex()]
        val error = Actions.answer(ctx, current, answer)
        if (error != null) return error
        val next = ctx.sequenceIndex() + 1
        if (next >= action.actions.size) {
            ctx.sequenceIndex(0)
            return null
        }
        ctx.sequenceIndex(next)
        ctx.goOn()
        return null
    }
}
