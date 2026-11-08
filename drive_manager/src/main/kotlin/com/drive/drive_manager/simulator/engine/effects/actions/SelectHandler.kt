package com.drive.drive_manager.simulator.engine.effects.actions

import kotlin.math.min
import com.drive.drive_manager.simulator.engine.frozenListOf
import com.drive.drive_manager.simulator.engine.frozenMapOf
import com.drive.drive_manager.simulator.engine.GameEvent
import com.drive.drive_manager.simulator.engine.effects.model.Action.Select
import com.drive.drive_manager.simulator.engine.effects.model.Memory
import com.drive.drive_manager.simulator.engine.effects.model.Selector
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler.Answer
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext
import com.drive.drive_manager.simulator.engine.effects.selection.Selection

/**
 * The player picks cards. `possible` asks for the whole amount, which is what a cost needs; at resolution it asks for as
 * many as there are (designer ruling 2026-11-01).
 */
class SelectHandler : ActionHandler<Select> {

    override fun possible(ctx: EffectContext, action: Select): Boolean {
        val selector = action.selector!!
        if (selector.special != null) return !Selection.candidates(ctx, selector, false).isEmpty()
        // It depends on the ally chosen by an action before it, not chosen yet
        if (selector.waitsFor(Memory.SUBJECT) && ctx.saved(Memory.SUBJECT).isEmpty()) return true
        val found = Selection.candidates(ctx, selector, action.target)
        if (selector.self() == Selector.Self.REQUIRE && found.none { ctx.isSelf(it) }) return false
        return found.size >= ctx.amount(selector.min)
    }

    override fun run(ctx: EffectContext, action: Select): Boolean {
        val selector = action.selector!!
        val valid = Selection.candidates(ctx, selector, action.target)
        if (valid.isEmpty()) {
            if (action.endsIfNone) {
                ctx.note("No hay aliados que puedan elegirse")
                ctx.endEffect()
            }
            return false
        }
        // "elige todos los enemigos": no choice, but they are targets
        if (selector.mode() == Selector.Mode.ALL) {
            answer(ctx, action, Answer(valid.map { it.instanceId }, frozenListOf()))
            return false
        }
        if (selector.special != null) {
            val bounds = ctx.specialBounds(selector.special, valid.size)
            ctx.ask(ctx.specialInstruction(selector.special), action.label, Selection.pool(ctx, selector), valid, bounds[0], bounds[1])
            if (bounds[2] > 0) ctx.limitLevelSum(bounds[2])
            return true
        }
        val max = min(ctx.amount(selector.max), valid.size)
        val min = min(ctx.amount(selector.min), max)
        val instruction = (if (action.target) "Elige " else "Selecciona ") + (if (min < max) "hasta " else "") + max + " " + ctx.describe(selector) +
                (if (selector.self() == Selector.Self.REQUIRE) ", incluida esta carta" else "") + "."
        val label = action.label ?: if (action.target) "Objetivo" else "Elegida"
        ctx.ask(instruction, label, Selection.pool(ctx, selector), valid, min, max)
        return true
    }

    override fun answer(ctx: EffectContext, action: Select, answer: Answer): String? {
        val picks = answer.picks!!
        val valid = Selection.candidates(ctx, action.selector!!, action.target)
        for (id in picks) {
            if (valid.none { it.instanceId == id }) return "Esa carta ya no se puede elegir"
        }
        if (action.selector.self() == Selector.Self.REQUIRE && !picks.isEmpty()
                && valid.none { ctx.isSelf(it) && picks.contains(it.instanceId) }) {
            return "Debes incluir esta carta"
        }
        if (action.selector.special != null) {
            val wrong = ctx.specialCheck(action.selector.special, picks)
            if (wrong != null) return wrong
        }
        val saveAs = action.saveAs ?: if (action.target) Memory.TARGETS else Memory.LAST
        ctx.save(saveAs, picks)
        ctx.save(Memory.ACTIVE_ALLIES, valid.filter { picks.contains(it.instanceId) && !ctx.isOpponents(it) && !it.tapped }.map { it.instanceId })
        ctx.done(!picks.isEmpty())
        if (action.target && !picks.isEmpty()) {
            val names = picks.map { ctx.name(ctx.find(it)) }
            ctx.event(GameEvent.TARGET, ctx.find(picks[0]), frozenMapOf<String, Any?>("targetIds", picks),
                    ctx.player()!!.username + " elige como objetivo a " + names.joinToString(", "))
        }
        return null
    }
}
