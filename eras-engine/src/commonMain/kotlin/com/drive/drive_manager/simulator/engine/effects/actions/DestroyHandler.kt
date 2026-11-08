package com.drive.drive_manager.simulator.engine.effects.actions

import kotlin.math.max
import kotlin.math.min
import com.drive.drive_manager.simulator.engine.JavaText
import com.drive.drive_manager.simulator.engine.effects.model.Action.Destroy
import com.drive.drive_manager.simulator.engine.effects.model.Selector
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler.Answer
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext
import com.drive.drive_manager.simulator.engine.effects.selection.Selection
import com.drive.drive_manager.simulator.room.CardSlot

/** "destruye" and "sacrifica" (rule 4.0.4.2: sacrificing is destroying and going to the Tributos). */
class DestroyHandler : ActionHandler<Destroy> {

    override fun possible(ctx: EffectContext, action: Destroy): Boolean {
        if (action.cards!!.fromMemory()) return true
        return candidates(ctx, action).size >= max(1, ctx.amount(action.cards.min))
    }

    override fun run(ctx: EffectContext, action: Destroy): Boolean {
        val selector = action.cards!!
        val valid = candidates(ctx, action)
        val max = min(ctx.amount(selector.max), valid.size)
        if (max <= 0) {
            ctx.done(false)
            return false
        }
        if (selector.mode() != Selector.Mode.CHOOSE) {
            apply(ctx, action, ArrayList(valid.subList(0, max)))
            return false
        }
        val min = min(ctx.amount(selector.min), max)
        val filter = selector.filter().describe()
        ctx.ask((if (action.sacrifice) "Sacrifica " else "Destruye ") + (if (min < max) "hasta " else "") + max + (if (max == 1) " criatura" else " criaturas")
                        + (if (filter.all { JavaText.isWhitespace(it) }) "" else " $filter") + ".",
                if (action.sacrifice) "Sacrificar" else "Destruir", Selection.pool(ctx, selector), valid, min, max)
        return true
    }

    override fun answer(ctx: EffectContext, action: Destroy, answer: Answer): String? {
        val valid = candidates(ctx, action)
        val picked = ArrayList<CardSlot>()
        for (id in answer.picks!!) {
            val card = valid.firstOrNull { it.instanceId == id } ?: return "Esa criatura ya no está en el campo"
            picked.add(card)
        }
        apply(ctx, action, picked)
        return null
    }

    private fun candidates(ctx: EffectContext, action: Destroy): List<CardSlot> {
        return Selection.candidates(ctx, action.cards!!, false).filter { ctx.onField(it) }
    }

    private fun apply(ctx: EffectContext, action: Destroy, cards: List<CardSlot>) {
        // Saved before: "de nivel menor que el aliado destruido" reads them once they are gone
        ctx.moved(cards)
        for (card in cards) ctx.destroy(card, action.sacrifice)
    }
}
