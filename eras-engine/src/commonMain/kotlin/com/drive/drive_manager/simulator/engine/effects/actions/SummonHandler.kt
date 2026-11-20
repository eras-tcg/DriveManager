package com.drive.drive_manager.simulator.engine.effects.actions

import kotlin.math.max
import kotlin.math.min
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
        return candidates(ctx, action).size >= max(1, ctx.amount(action.cards!!.min))
    }

    override fun run(ctx: EffectContext, action: Summon): Boolean {
        val selector = action.cards!!
        val valid = candidates(ctx, action)
        if (valid.isEmpty()) {
            ctx.note("No hay criaturas que este efecto pueda invocar")
            return false
        }
        val max = min(ctx.amount(selector.max), valid.size)
        if (selector.mode() != Selector.Mode.CHOOSE) {
            apply(ctx, action, ArrayList(valid.subList(0, max)))
            return false
        }
        val reduce = ctx.amount(action.reduce)
        val min = min(ctx.amount(selector.min), max)
        val filter = selector.filter().describe()
        val cost = if (reduce >= Summon.FREE) " sin pagar su coste" else if (reduce > 0) " reduciendo su coste en $reduce" else " pagando su coste"
        if (action.orPlay) {
            ctx.ask("Juega o invoca " + (if (min < max) "hasta " else "") + max + (if (max == 1) " carta" else " cartas")
                    + (if (filter.all { JavaText.isWhitespace(it) }) "" else " $filter") + " de " + ctx.describeZones(selector.zones) + cost + ".",
                    "Jugar o invocar", Selection.pool(ctx, selector), valid, min, max)
            return true
        }
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
        val total = picked.sumOf { max(0, (if ("creature" == it.cardType) ctx.summonCost(it) else ctx.playCost(it)) - reduce) }
        if (!ctx.canPay(total)) return "No tienes gemas para pagarlas todas ($total)"
        apply(ctx, action, picked)
        return null
    }

    private fun candidates(ctx: EffectContext, action: Summon): List<CardSlot> {
        val reduce = ctx.amount(action.reduce)
        return Selection.candidates(ctx, action.cards!!, false).filter { c ->
            val cost = c.cost
            // "juega o invoca": a utility or structure is played as "juega" plays it
            if (action.orPlay && ("utility" == c.cardType || "structure" == c.cardType))
                return@filter ctx.canPay(max(0, ctx.playCost(c) - reduce)) && ctx.canBePlayed(c)
                        && (action.cards.mode() == Selector.Mode.SELF || !ctx.isSelf(c) || ctx.delayed())
            "creature" == c.cardType && cost != null && ctx.canPay(max(0, ctx.summonCost(c) - reduce))
                    // An effect does not act on its own card, unless it says "esta carta" or comes back later
                    && (action.cards.mode() == Selector.Mode.SELF || !ctx.isSelf(c) || ctx.delayed())
        }
    }

    private fun apply(ctx: EffectContext, action: Summon, cards: List<CardSlot>) {
        val reduce = ctx.amount(action.reduce)
        for (card in cards) if ("creature" == card.cardType) ctx.summon(card, reduce, action.toFront) else ctx.play(card, reduce)
        ctx.done(!cards.isEmpty())
    }
}
