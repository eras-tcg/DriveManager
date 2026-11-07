package com.drive.drive_manager.simulator.engine.effects.actions

import com.drive.drive_manager.simulator.engine.effects.model.Action.Play
import com.drive.drive_manager.simulator.engine.effects.model.Selector
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler.Answer
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext
import com.drive.drive_manager.simulator.engine.effects.selection.Selection
import com.drive.drive_manager.simulator.room.CardSlot

/** "juega": a utility or structure, only if its "En Juego" can be activated (designer ruling 2026-10-30). */
class PlayHandler : ActionHandler<Play> {

    override fun possible(ctx: EffectContext, action: Play): Boolean {
        return candidates(ctx, action).size >= Math.max(1, ctx.amount(action.cards!!.min))
    }

    override fun run(ctx: EffectContext, action: Play): Boolean {
        val selector = action.cards!!
        val valid = candidates(ctx, action)
        if (valid.isEmpty()) {
            ctx.note("No hay cartas que este efecto pueda jugar")
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
        val cost = if (reduce >= Play.FREE) " sin pagar su coste" else if (reduce > 0) " reduciendo su coste en $reduce" else " pagando su coste"
        ctx.ask("Juega " + (if (min < max) "hasta " else "") + max + (if (max == 1) " carta" else " cartas") + (if (filter.all { Character.isWhitespace(it) }) "" else " $filter")
                + " de " + ctx.describeZones(selector.zones) + cost + ".", "Jugar", Selection.pool(ctx, selector), valid, min, max)
        return true
    }

    override fun answer(ctx: EffectContext, action: Play, answer: Answer): String? {
        val valid = candidates(ctx, action)
        val picked = ArrayList<CardSlot>()
        for (id in answer.picks!!) {
            val card = valid.firstOrNull { it.instanceId == id } ?: return "Ya no puedes jugar esa carta"
            picked.add(card)
        }
        val reduce = ctx.amount(action.reduce)
        val total = picked.sumOf { Math.max(0, (it.cost ?: 0) - reduce) }
        if (!ctx.canPay(total)) return "No tienes gemas para pagarlas todas ($total)"
        apply(ctx, action, picked)
        return null
    }

    private fun candidates(ctx: EffectContext, action: Play): List<CardSlot> {
        val reduce = ctx.amount(action.reduce)
        return Selection.candidates(ctx, action.cards!!, false).filter { c ->
            ("utility" == c.cardType || "structure" == c.cardType) && ctx.canPay(Math.max(0, (c.cost ?: 0) - reduce)) && ctx.canBePlayed(c)
                    // An effect does not act on its own card, unless it says "esta carta" or comes back later
                    && (action.cards.mode() == Selector.Mode.SELF || !ctx.isSelf(c) || ctx.delayed())
        }
    }

    private fun apply(ctx: EffectContext, action: Play, cards: List<CardSlot>) {
        val reduce = ctx.amount(action.reduce)
        for (card in cards) ctx.play(card, reduce)
        ctx.done(!cards.isEmpty())
    }
}
