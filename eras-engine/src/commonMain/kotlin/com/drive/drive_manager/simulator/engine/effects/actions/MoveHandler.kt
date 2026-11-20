package com.drive.drive_manager.simulator.engine.effects.actions

import kotlin.math.max
import kotlin.math.min
import com.drive.drive_manager.simulator.engine.frozenListOf
import com.drive.drive_manager.simulator.engine.JavaText
import com.drive.drive_manager.simulator.engine.GameRandom
import com.drive.drive_manager.simulator.engine.effects.model.Action.Move
import com.drive.drive_manager.simulator.engine.effects.model.Memory
import com.drive.drive_manager.simulator.engine.effects.model.Selector
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler.Answer
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext
import com.drive.drive_manager.simulator.engine.effects.selection.Selection
import com.drive.drive_manager.simulator.room.CardSlot

/**
 * Cards go to a zone. Picking them is not a target: an effect that says "elige" uses Select first. A cost needs the whole
 * amount (`possible`); at resolution as many as there are move (designer ruling 2026-11-01).
 */
class MoveHandler : ActionHandler<Move> {

    override fun possible(ctx: EffectContext, action: Move): Boolean {
        val selector = action.cards!!
        // Its cards depend on the ally chosen by an action before it, which has not been chosen yet
        if (selector.waitsFor(Memory.SUBJECT) && ctx.saved(Memory.SUBJECT).isEmpty()) return true
        if (selector.zones!! == "looked" || selector.fromMemory()) return true
        // "tributa todos los materiales de esta carta" with none: an empty action, still done (designer, Samael): a cost paid
        if (selector.mode() == Selector.Mode.ALL && ctx.amount(selector.min) <= 0 && selector.self() != Selector.Self.REQUIRE) return true
        val found = candidates(ctx, action)
        if (selector.self() == Selector.Self.REQUIRE && found.none { ctx.isSelf(it) }) return false
        // "N cartas de distinto nivel": as many different levels
        if (action.distinctLevels) return found.mapNotNull { it.getLevel() }.distinct().size >= max(1, ctx.amount(selector.min))
        return found.size >= max(1, ctx.amount(selector.min))
    }

    override fun run(ctx: EffectContext, action: Move): Boolean {
        val selector = action.cards!!
        if (Move.DECK_TOP_OR_BOTTOM == action.to) {
            if (ctx.looked().isEmpty()) return false
            // One choice: their order and where they go (the first one of the order ends up highest)
            val deck = if (ctx.lookedAreOpponents()) "del mazo de tu oponente" else "de tu mazo"
            ctx.ask(if (ctx.looked().size > 1) "Ordena las cartas (la de la izquierda queda más arriba) y elige si van al tope o al fondo $deck."
                    else "Elige si la carta va al tope o al fondo $deck.", null, ctx.looked(), ctx.looked(), 0, 1)
            ctx.asksTopOrBottom()
            return true
        }
        val valid = candidates(ctx, action)
        val max = min(ctx.amount(selector.max), valid.size)
        // With nothing to pick, the rest of the looked cards are still ordered
        if (action.rest != null && !ctx.looked().isEmpty()) {
            val min = min(ctx.amount(selector.min), max)
            ctx.ask(instruction(ctx, action, min, max), action.label ?: label(action.to), ctx.looked(), valid, min, max)
            if (Move.DECK_TOP_KEPT != action.rest) ctx.asksOrder(Move.DECK_TOP == action.rest)
            return true
        }
        if (max <= 0) {
            ctx.moved(frozenListOf())
            return false
        }
        if (selector.mode() != Selector.Mode.CHOOSE) {
            move(ctx, action, valid.subList(0, max))
            return false
        }
        val min = min(ctx.amount(selector.min), max)
        ctx.ask(instruction(ctx, action, min, max), action.label ?: label(action.to), Selection.pool(ctx, selector), valid, min, max)
        if (selector.self() == Selector.Self.REQUIRE) valid.firstOrNull { ctx.isSelf(it) }?.let { ctx.requireInChoice(it) }
        if (action.distinctLevels) ctx.asksDistinctLevels()
        return true
    }

    override fun answer(ctx: EffectContext, action: Move, answer: Answer): String? {
        if (Move.DECK_TOP_OR_BOTTOM == action.to) {
            ctx.lookedRestTo(if (answer.picks!!.isEmpty()) Move.DECK_BOTTOM else Move.DECK_TOP, answer.order)
            return null
        }
        val valid = candidates(ctx, action)
        val picked = ArrayList<CardSlot>()
        for (id in answer.picks!!) {
            val card = valid.firstOrNull { it.instanceId == id } ?: return "Esa carta ya no está disponible"
            picked.add(card)
        }
        if (action.cards!!.self() == Selector.Self.REQUIRE && !picked.isEmpty() && picked.none { ctx.isSelf(it) }) return "Debes incluir esta carta"
        if (action.sameName && picked.map { it.cardName?.lowercase() }.toSet().size > 1) return "Las cartas deben ser copias de una misma carta"
        if (action.distinctLevels && (picked.any { it.getLevel() == null } || picked.map { it.getLevel() }.toSet().size < picked.size))
            return "Las cartas deben ser de distinto nivel"
        if (action.distinctLevels && picked.size < min(ctx.amount(action.cards.min), candidates(ctx, action).size)) return "Elige " + ctx.amount(action.cards.min) + " cartas"
        move(ctx, action, picked)
        if (Move.DECK_TOP_KEPT == action.rest) ctx.lookedRestTo(Move.DECK_TOP, null)
        else if (action.rest != null) ctx.lookedRestTo(action.rest, answer.order)
        return null
    }

    /** Never a card already where it is going; "N copias de una criatura": only cards with at least N copies among them. */
    private fun candidates(ctx: EffectContext, action: Move): List<CardSlot> {
        val found = Selection.candidates(ctx, action.cards!!, false).filter { !ctx.alreadyIn(it, action.to) && !ctx.unaffected(it) }
        if (!action.sameName) return found
        val copies = max(1, ctx.amount(action.cards.min))
        val byName = found.groupBy { it.cardName?.lowercase() }
        return found.filter { (byName[it.cardName?.lowercase()]?.size ?: 0) >= copies }
    }

    private fun move(ctx: EffectContext, action: Move, cards: List<CardSlot>) {
        val ordered = ArrayList(cards)
        if (action.random) GameRandom.shuffle(ordered, ctx.random())
        val done = ctx.move(ordered, action.to, action.exhausted, action.faceDown, action.withResources)
        // "esta carta" is not what "si tributaste…" or "el material tributado" read
        if (action.cards!!.mode() == Selector.Mode.SELF) ctx.done(!done.isEmpty())
        else ctx.moved(done)
    }

    private fun label(to: String?): String {
        return when (to!!) {
            Move.TRIBUTES -> "Tributar"
            Move.DISCARD -> "Descartar"
            Move.DECK_BOTTOM -> "Al fondo"
            Move.DECK_TOP -> "Al tope"
            Move.HAND -> "A tu mano"
            Move.FRONT -> "A la línea frontal"
            Move.BACK -> "A la retaguardia"
            Move.LIFE_TOP -> "A tus vidas"
            Move.LIFE_BOTTOM -> "Al fondo de las vidas"
            else -> "Elegida"
        }
    }

    private fun instruction(ctx: EffectContext, action: Move, min: Int, max: Int): String {
        val what = (if (min < max) "hasta " else "") + max + " " + (if (max == 1) "carta" else "cartas")
        val filter = action.cards!!.filter().describe()
        // The cards looked at are the ones on the sheet: no "de …" for them
        val zones = ctx.describeZones(action.cards.zones)!!
        val from = if (action.cards.zones == "looked") "" else if (action.cards.owner == Selector.Owner.OPPONENT) " de " + zones.replace("tus ", "las ").replace("tu ", "la ") + " oponentes"
        else " de $zones"
        var cards = what + (if (filter.all { JavaText.isWhitespace(it) }) "" else " $filter") + from
        if (action.cards.self() == Selector.Self.REQUIRE) cards += ", incluida esta carta"
        if (action.distinctLevels) cards += " de distinto nivel"
        val random = if (action.random) " en orden aleatorio" else ""
        if (action.to!!.startsWith("materials:") || action.to.startsWith("resources:")) {
            return "Añade " + cards + (if (action.withResources) " con todos sus recursos" else "") + " como " +
                    (if (action.to.startsWith("materials:")) "material." else "recurso.")
        }
        if (Move.DECK_TOP_KEPT == action.rest) return instructionOf(action, cards, random) + " El resto vuelve al tope de tu mazo en su orden."
        // The rest, when the player orders it, is asked on its own sheet afterwards: this one says only what is done now
        return instructionOf(action, cards, random)
    }

    private fun instructionOf(action: Move, cards: String, random: String): String {
        return when (action.to!!) {
            Move.TRIBUTES -> "Tributa $cards."
            Move.DISCARD -> "Descarta $cards."
            Move.DECK_BOTTOM -> "Envía $cards al fondo de tu mazo$random."
            Move.DECK_TOP -> "Coloca $cards al tope de tu mazo."
            Move.HAND -> "Toma $cards para tu mano."
            Move.FRONT -> "Coloca " + cards + " en tu línea frontal" + (if (action.exhausted) ", agotada." else ".")
            Move.BACK -> "Coloca " + cards + " en tu retaguardia" + (if (action.exhausted) ", agotada." else ".")
            Move.LIFE_TOP -> "Coloca " + cards + " al tope de tus vidas" + (if (action.faceDown) "." else ", bocarriba.")
            Move.LIFE_BOTTOM -> "Coloca " + cards + " al fondo de las vidas" + (if (action.faceDown) "." else ", bocarriba.")
            else -> "Elige $cards."
        }
    }
}
