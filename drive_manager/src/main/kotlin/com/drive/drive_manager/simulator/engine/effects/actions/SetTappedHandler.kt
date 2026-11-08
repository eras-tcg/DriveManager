package com.drive.drive_manager.simulator.engine.effects.actions

import com.drive.drive_manager.simulator.engine.JavaText
import com.drive.drive_manager.simulator.engine.effects.model.Action.SetTapped
import com.drive.drive_manager.simulator.engine.effects.model.Selector
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler.Answer
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext
import com.drive.drive_manager.simulator.engine.effects.selection.Selection
import com.drive.drive_manager.simulator.room.CardSlot

/** "agota" and "reactiva": the rules on who can be exhausted or reactivated are in the context. */
class SetTappedHandler : ActionHandler<SetTapped> {

    override fun possible(ctx: EffectContext, action: SetTapped): Boolean {
        if (action.cards!!.fromMemory()) return true
        return candidates(ctx, action).size >= Math.max(1, ctx.amount(action.cards.min))
    }

    override fun run(ctx: EffectContext, action: SetTapped): Boolean {
        val selector = action.cards!!
        val valid = candidates(ctx, action)
        val max = Math.min(ctx.amount(selector.max), valid.size)
        if (max <= 0) {
            ctx.done(false)
            return false
        }
        if (selector.mode() != Selector.Mode.CHOOSE) {
            apply(ctx, action, valid.subList(0, max))
            return false
        }
        val min = Math.min(ctx.amount(selector.min), max)
        val verb = if (action.tapped) "Agota " else "Reactiva "
        val filter = selector.filter().describe()
        ctx.ask(verb + (if (min < max) "hasta " else "") + max + (if (max == 1) " carta" else " cartas") + (if (filter.all { JavaText.isWhitespace(it) }) "" else " $filter") + ".",
                if (action.tapped) "Agotar" else "Reactivar", Selection.pool(ctx, selector), valid, min, max)
        return true
    }

    override fun answer(ctx: EffectContext, action: SetTapped, answer: Answer): String? {
        val valid = candidates(ctx, action)
        val picked = ArrayList<CardSlot>()
        for (id in answer.picks!!) {
            val card = valid.firstOrNull { it.instanceId == id } ?: return "Esa carta ya no se puede " + (if (action.tapped) "agotar" else "reactivar")
            picked.add(card)
        }
        apply(ctx, action, picked)
        return null
    }

    private fun candidates(ctx: EffectContext, action: SetTapped): List<CardSlot> {
        return Selection.candidates(ctx, action.cards!!, false)
                .filter { ctx.onField(it) && !ctx.unaffected(it) && (if (action.tapped) ctx.canTap(it) else ctx.canUntap(it)) }
    }

    private fun apply(ctx: EffectContext, action: SetTapped, cards: List<CardSlot>) {
        for (card in ArrayList(cards)) ctx.setTapped(card, action.tapped)
        ctx.done(!cards.isEmpty())
        ctx.counted(cards.size)
    }
}
