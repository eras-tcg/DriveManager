package com.drive.drive_manager.simulator.engine.effects.selection

import com.drive.drive_manager.simulator.engine.effects.model.Selector
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.PlayerState

/**
 * Resolves a [Selector], with the rules on what an effect may reach in one place: the Retaguardia cannot be reached by
 * the opponent's effects and a creature in the Retaguardia cannot affect enemies (rule 4.0.6.2); a card that cannot be a target
 * can still be picked by an effect that does not say "elige" (rule 11.10).
 */
object Selection {

    /** The cards shown for the choice: what is in the selector's zones within reach, whether or not it can be picked. */
    @JvmStatic
    fun pool(ctx: EffectContext, selector: Selector): List<CardSlot> {
        if (selector.special != null) return ctx.specialShown(selector.special)
        val pool = ArrayList<CardSlot>()
        if (selector.owner != Selector.Owner.OPPONENT) addZones(pool, ctx, ctx.player(), selector.zones, false)
        if (selector.owner != Selector.Owner.YOU && ctx.opponent() != null) addZones(pool, ctx, ctx.opponent(), selector.zones, true)
        return pool
    }

    /** The cards that can be picked now. */
    @JvmStatic
    fun candidates(ctx: EffectContext, selector: Selector, target: Boolean): List<CardSlot> {
        if (selector.special != null) return ctx.specialOptions(selector.special)
        val found = ArrayList<CardSlot>()
        for (card in pool(ctx, selector)) {
            val self = ctx.isSelf(card)
            if (selector.mode() == Selector.Mode.SELF && !self) continue
            if (!selector.filter().matches(card) && !(self && selector.self() == Selector.Self.REQUIRE)) continue
            if (selector.self() == Selector.Self.EXCLUDE && self) continue
            if (selector.relative != null && !relative(ctx, selector.relative, card)) continue
            if (ctx.isOpponents(card)) {
                // An enemy is a creature
                if (!ctx.isCreature(card)) continue
                if (target && ctx.immune(card)) continue
            }
            found.add(card)
        }
        if (selector.mode() == Selector.Mode.NEWEST) java.util.Collections.reverse(found)
        return found
    }

    private fun relative(ctx: EffectContext, r: Selector.Relative, card: CardSlot): Boolean {
        if (r.level != null && ctx.amount(r.level) != card.getLevel()) return false
        if (r.maxLevel != null) {
            val level = card.getLevel()
            if (level == null || level > ctx.amount(r.maxLevel)) return false
        }
        if (r.to == null) return true
        val other = ctx.relativeTo(r.to) ?: return false
        if (r.compareLevel != null) {
            val cardLevel = card.getLevel() ?: return false
            val level = ctx.levelOf(other) + r.plus
            val holds = when (r.compareLevel) {
                Selector.Compare.EQUAL -> cardLevel == level
                Selector.Compare.AT_MOST -> cardLevel <= level
                Selector.Compare.LESS -> cardLevel < level
                Selector.Compare.GREATER -> cardLevel > level
            }
            if (!holds) return false
        }
        if (r.compareStrength == Selector.Compare.LESS && ctx.strength(card) >= ctx.strength(other)) return false
        val name = card.cardName
        if (r.differentName && name != null && name.equals(other.cardName, ignoreCase = true)) return false
        if (r.sameName && (name == null || !name.equals(other.cardName, ignoreCase = true))) return false
        if (r.sharesClass) {
            val theirs: List<String> = other.classes ?: java.util.List.of()
            val classes = card.classes
            if (classes == null || classes.none { t -> theirs.any { t.equals(it, ignoreCase = true) } }) return false
        }
        return true
    }

    /** As Java's `String.split(",")`: the empty strings at the end are left out, unless nothing matched. */
    private fun splitZones(zones: String): List<String> {
        val parts = zones.split(",")
        return if (parts.size == 1) parts else parts.dropLastWhile { it.isEmpty() }
    }

    private fun addZones(into: MutableList<CardSlot>, ctx: EffectContext, whose: PlayerState?, zones: String?, enemy: Boolean) {
        for (raw in splitZones(zones!!)) {
            // As Java's trim(): what is at most a space
            val zone = raw.trim { it <= ' ' }
            if (zone.startsWith("saved:")) {
                if (!enemy) for (id in ctx.saved(zone.substring("saved:".length))) {
                    val card = ctx.find(id)
                    // Rule 11.10.2: a target that left the enemy's Línea frontal is no longer valid
                    if (card != null && !(ctx.isOpponents(card) && !ctx.inFrontLine(card))) into.add(card)
                }
                continue
            }
            if (zone == "pickableAllies" || zone == "pickableAttackers" || zone == "attackers") {
                if (!enemy) into.addAll(ctx.pickable(zone))
                continue
            }
            if (zone == "clash") {
                val card = ctx.clashing()
                // Taken once: it belongs to the opponent, whichever side is being read
                if (card != null && !into.contains(card)) into.add(card)
                continue
            }
            if (zone == "thisCard") {
                val card = if (enemy) null else ctx.find(ctx.selfId())
                if (card != null && ctx.onField(card)) into.add(card)
                continue
            }
            if (zone == "subjectOrSummoned") {
                val card = if (enemy) null else ctx.subjectOrSummoned()
                if (card != null) into.add(card)
                continue
            }
            if (zone == "subjectOrSelf") {
                val card = if (enemy) null else ctx.subjectOrSelf()
                if (card != null) into.add(card)
                continue
            }
            if (zone == "eventOnField") {
                val card = if (enemy) null else ctx.eventCardOnField()
                if (card != null && !(ctx.isOpponents(card) && !ctx.inFrontLine(card))) into.add(card)
                continue
            }
            if (zone == "event") {
                val card = if (enemy) null else ctx.eventCard()
                if (card != null) into.add(card)
                continue
            }
            if (zone == "looked") {
                if (!enemy) into.addAll(ctx.looked())
                continue
            }
            if (zone.startsWith("materials") || zone.startsWith("resources")) {
                if (!enemy) into.addAll(ctx.attached(zone))
                continue
            }
            val allies = zone == "allies"
            val onField = allies || zone == "field" || zone == "frontLine" || zone == "backLine"
            if (!onField) {
                into.addAll(ctx.zone(whose, zone))
                continue
            }
            // Rule 4.0.6.2: effects reach only the enemies of the Línea frontal, and only from yours
            if (enemy && ctx.inRetaguardia()) continue
            for (card in whose!!.field) {
                val front = ctx.inFrontLine(card)
                if (allies && !ctx.isCreature(card)) continue
                if (zone == "frontLine" && !front) continue
                if (zone == "backLine" && front) continue
                if (enemy && !front) continue
                into.add(card)
            }
        }
    }
}
