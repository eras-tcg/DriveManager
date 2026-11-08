package com.drive.drive_manager.simulator.engine.effects.actions

import com.drive.drive_manager.simulator.engine.JavaText
import com.drive.drive_manager.simulator.engine.effects.model.Action.Summon
import com.drive.drive_manager.simulator.engine.effects.model.Selector
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler.Answer
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext
import com.drive.drive_manager.simulator.engine.effects.selection.Selection
import com.drive.drive_manager.simulator.room.CardSlot

/** "invoca": a summon by effect (rule 9.1.0.2): pays the cost, enters the Retaguardia unless it says otherwise, then the regulatory draw. */
class SummonHandler : ActionHandler<Summon> {

    override fun possible(ctx: EffectContext, action: Summon): Boolean {
        return candidates(ctx, action).size >= Math.max(1, ctx.amount(action.cards!!.min))
    }

    override fun run(ctx: EffectContext, action: Summon): Boolean {
        val selector = action.cards!!
        val valid = candidates(ctx, action)
        if (valid.isEmpty()) {
            ctx.note("No hay criaturas que este efecto pueda invocar")
            return false
        }
        val max = Math.min(ctx.amount(selector.max), valid.size)
        if (selector.mode() != Selector.Mode.CHOOSE) {
            apply(ctx, action, ArrayList(valid.subList(0, max)))
            return false
        }
        val reduce = ctx.amount(action.reduce)
        val min = Math.min(ctx.amount(selector.min), max)
        val filter = selector.filter().describe()
        val cost = if (reduce >= Summon.FREE) " sin pagar su coste" else if (reduce > 0) " reduciendo su coste en $reduce" else " pagando su coste"
        ctx.ask("Invoca " + (if (min < max) "hasta " else "") + max + (if (max == 1) " criatura" else " criaturas") + (if (filter.all { JavaText.isWhitespace(it) }) "" else " $filter")
                + " de " + ctx.describeZones(selector.zones) + cost + ". Entra en " + (if (action.toFront) "tu línea frontal" else "Retaguardia") + " y robas 1 carta.", "Invocar", Selection.pool(ctx, selector), valid, min, max)
        return true
    }

    override fun answer(ctx: EffectContext, action: Summon, answer: Answer): String? {
        val valid = candidates(ctx, action)
        val picked = ArrayList<CardSlot>()
        for (id in answer.picks!!) {
            val card = valid.firstOrNull { it.instanceId == id } ?: return "Ya no puedes invocar esa carta"
            picked.add(card)
        }
        val reduce = ctx.amount(action.reduce)
        val total = picked.sumOf { Math.max(0, (it.cost ?: 0) - reduce) }
        if (!ctx.canPay(total)) return "No tienes gemas para pagarlas todas ($total)"
        apply(ctx, action, picked)
        return null
    }

    private fun candidates(ctx: EffectContext, action: Summon): List<CardSlot> {
        val reduce = ctx.amount(action.reduce)
        return Selection.candidates(ctx, action.cards!!, false).filter { c ->
            val cost = c.cost
            "creature" == c.cardType && cost != null && ctx.canPay(Math.max(0, cost - reduce))
                    // An effect does not act on its own card, unless it says "esta carta" or comes back later
                    && (action.cards.mode() == Selector.Mode.SELF || !ctx.isSelf(c) || ctx.delayed())
        }
    }

    private fun apply(ctx: EffectContext, action: Summon, cards: List<CardSlot>) {
        val reduce = ctx.amount(action.reduce)
        for (card in cards) ctx.summon(card, reduce, action.toFront)
        ctx.done(!cards.isEmpty())
    }
}
