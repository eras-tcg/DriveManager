package com.drive.drive_manager.simulator.engine.effects.actions

import kotlin.math.max
import kotlin.math.min
import com.drive.drive_manager.simulator.engine.effects.model.Action.Flip
import com.drive.drive_manager.simulator.engine.effects.model.Selector
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler.Answer
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext
import com.drive.drive_manager.simulator.engine.effects.selection.Selection
import com.drive.drive_manager.simulator.room.CardSlot

/**
 * "revela 1 de tus vidas", "revela 1 vida oponente", "voltea una de tus vidas", "coloca bocabajo …": the cards picked are turned over. A card
 * already as it would end is not offered ("revela": the face-down ones).
 */
class FlipHandler : ActionHandler<Flip> {

    override fun possible(ctx: EffectContext, action: Flip): Boolean {
        if (action.cards!!.fromMemory()) return true
        return candidates(ctx, action).size >= max(1, ctx.amount(action.cards.min))
    }

    override fun run(ctx: EffectContext, action: Flip): Boolean {
        val selector = action.cards!!
        val valid = candidates(ctx, action)
        val max = min(ctx.amount(selector.max), valid.size)
        if (max <= 0) {
            ctx.done(false)
            return false
        }
        if (selector.mode() != Selector.Mode.CHOOSE) {
            apply(ctx, action, valid.subList(0, max))
            return false
        }
        val min = min(ctx.amount(selector.min), max)
        val verb = if (action.toggle) "Voltea " else if (action.faceUp) "Revela " else "Coloca bocabajo "
        ctx.ask(verb + (if (min < max) "hasta " else "") + max + " " + (if (max == 1) "carta" else "cartas") + " de " + (if (selector.owner == Selector.Owner.OPPONENT) "las vidas oponentes" else ctx.describeZones(selector.zones)) + ".",
            if (!action.faceUp && !action.toggle) "Bocabajo" else "Voltear", Selection.pool(ctx, selector), valid, min, max)
        return true
    }

    override fun answer(ctx: EffectContext, action: Flip, answer: Answer): String? {
        val valid = candidates(ctx, action)
        val picked = ArrayList<CardSlot>()
        for (id in answer.picks!!) {
            picked.add(valid.firstOrNull { it.instanceId == id } ?: return "Esa carta ya no se puede voltear")
        }
        apply(ctx, action, picked)
        return null
    }

    private fun candidates(ctx: EffectContext, action: Flip): List<CardSlot> {
        val found = Selection.candidates(ctx, action.cards!!, false)
        if (action.toggle) return found
        return if (action.faceUp) found.filter { it.faceDown } else found.filter { !it.faceDown }
    }

    private fun apply(ctx: EffectContext, action: Flip, cards: List<CardSlot>) {
        for (card in cards) ctx.flip(card, if (action.toggle) null else action.faceUp)
        ctx.done(cards.isNotEmpty())
    }
}
