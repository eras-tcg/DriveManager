package com.drive.drive_manager.simulator.engine

import kotlin.jvm.JvmStatic
import com.drive.drive_manager.simulator.engine.effects.model.Action
import com.drive.drive_manager.simulator.engine.effects.model.Action.Move
import com.drive.drive_manager.simulator.engine.effects.model.Memory
import com.drive.drive_manager.simulator.engine.effects.model.Selector
import com.drive.drive_manager.simulator.engine.effects.model.SpecialRule

/**
 * Usual printed phrases composed from basic actions, for code that builds steps (the engine's keyword effects, rules that
 * run a step, and the tests). Cards themselves are JSON files (`eras-engine/cards`). No logic here: each one only says which
 * action and which selector.
 */
object Compose {

    private fun move(cards: Selector?, to: String?): EffectStep {
        return EffectStep.Do(Action.Move(cards, to, false, null, false))
    }

    private fun hand(filter: CardFilter?, count: Int): Selector {
        return Selector.yours("hand", filter, count).with(Selector.Self.EXCLUDE)
    }

    // ------------------------------------------------------------------ Choosing enemies

    /** "elige N enemigos [filtro]": the literal word makes them targets (designer ruling 2026-11-01). */
    @JvmStatic
    fun chooseEnemy(count: Int, filter: CardFilter?): EffectStep {
        return chooseEnemy(count, filter, false)
    }

    @JvmStatic
    fun chooseEnemy(count: Int, filter: CardFilter?, upTo: Boolean): EffectStep {
        val enemies = Selector.enemies(filter, count)
        return EffectStep.Do(Action.Select(if (upTo) enemies.upTo() else enemies, true, null, null))
    }

    // ------------------------------------------------------------------ To the Tributos

    /** "tributa N cartas de tu mano" (not the effect's own card when it is in the hand). */
    @JvmStatic
    fun tributeFromHand(count: Int): EffectStep {
        return move(hand(CardFilter.any(), count), Move.TRIBUTES)
    }

    /** "tributa "X" de los recursos de esta carta". */
    @JvmStatic
    fun tributeResource(name: String?): EffectStep {
        return move(Selector.yours("resources", CardFilter.any().named(name), 1).`as`(Selector.Mode.TOP), Move.TRIBUTES)
    }

    @JvmStatic
    fun tributeResources(count: Int, filter: CardFilter?): EffectStep {
        return move(Selector.yours("resources", filter, count), Move.TRIBUTES)
    }

    // ------------------------------------------------------------------ This card

    @JvmStatic
    fun moveSelfToFront(): EffectStep {
        return move(Selector.self("backLine"), Move.FRONT)
    }

    /** "añade esta carta como material a un aliado" (the ally chosen before). */
    @JvmStatic
    fun attachSelfToSubject(): EffectStep {
        return move(Selector.self("field,tributeZone"), "materials:subject")
    }

    // ------------------------------------------------------------------ Looked cards: one choice, the rest ordered in it

    private fun fromLooked(filter: CardFilter?, min: Int, max: Int, to: String?, rest: String?, faceDown: Boolean): EffectStep {
        val looked = Selector("looked", Selector.Owner.YOU, filter, Amount.Fixed(min), Amount.Fixed(max), Selector.Self.ANY, Selector.Mode.CHOOSE)
        return EffectStep.Do(Action.Move(looked, to, false, null, false, false, faceDown, rest))
    }

    /** "de ellas toma hasta N [filtro]" leaving the rest with the effect. */
    @JvmStatic
    fun takeFromLooked(max: Int, filter: CardFilter?): EffectStep {
        return fromLooked(filter, 0, max, Move.HAND, null, false)
    }

    // ------------------------------------------------------------------ Simple actions

    private fun act(action: Action?): EffectStep {
        return EffectStep.Do(action)
    }

    @JvmStatic
    fun draw(count: Int): EffectStep {
        return act(Action.Draw(Amount.Fixed(count), false))
    }

    @JvmStatic
    fun gainGems(count: Int): EffectStep {
        return act(Action.Gems(Amount.Fixed(count)))
    }

    @JvmStatic
    fun damageByEffect(amount: Int): EffectStep {
        return act(Action.Damage(Amount.Fixed(amount)))
    }

    // ------------------------------------------------------------------ Exhausting and reactivating

    private fun setTapped(cards: Selector?, tapped: Boolean, required: Boolean): EffectStep {
        return act(Action.SetTapped(cards, tapped, required))
    }

    private val TARGETS = Selector.saved(Memory.TARGETS)
    private val SUMMONED = Selector.saved(Memory.SUMMONED)
    private val SUBJECT_OR_SELF = Selector.all("subjectOrSelf", CardFilter.any())

    @JvmStatic
    fun tapSelf(): EffectStep {
        return setTapped(Selector.self("field"), true, false)
    }

    @JvmStatic
    fun untapAlly(): EffectStep {
        return untapAlly(CardFilter.any())
    }

    @JvmStatic
    fun untapAlly(filter: CardFilter?): EffectStep {
        return setTapped(Selector.yours("allies", filter, 1), false, true)
    }

    @JvmStatic
    fun untapAllies(max: Int): EffectStep {
        return untapAllies(max, CardFilter.any())
    }

    @JvmStatic
    fun untapAllies(max: Int, filter: CardFilter?): EffectStep {
        return setTapped(Selector.yours("allies", filter, max).upTo(), false, false)
    }

    // ------------------------------------------------------------------ Strength, abilities and restrictions for a while

    private fun strength(cards: Selector?, amount: Amount?, until: Action.Until?): EffectStep {
        return act(Action.Modify(cards, amount, false, null, until))
    }

    private fun keyword(cards: Selector?, keyword: String?, until: Action.Until?): EffectStep {
        return act(Action.Modify(cards, null, false, keyword, until))
    }

    private fun until(until: String?): Action.Until {
        return when (until!!) {
            "nextTurn" -> Action.Until.NEXT_TURN
            "attack" -> Action.Until.ATTACK
            else -> Action.Until.TURN
        }
    }

    @JvmStatic
    fun gainStrength(amount: Int): EffectStep {
        return strength(SUBJECT_OR_SELF, Amount.Fixed(amount), Action.Until.TURN)
    }

    // ------------------------------------------------------------------ Destroying and sacrificing

    private fun destroy(cards: Selector?, required: Boolean): EffectStep {
        return act(Action.Destroy(cards, false, required))
    }

    private fun sacrifice(cards: Selector?, required: Boolean): EffectStep {
        return act(Action.Destroy(cards, true, required))
    }

    @JvmStatic
    fun sacrificeAlly(): EffectStep {
        return sacrificeAlly(false, false)
    }

    @JvmStatic
    fun sacrificeAlly(toUntapAnother: Boolean): EffectStep {
        return sacrificeAlly(toUntapAnother, false)
    }

    /** `toUntapAnother`: "sacrifica 1 aliado para reactivar otro": it needs another that can be reactivated, the old step until relative filters. */
    @JvmStatic
    fun sacrificeAlly(toUntapAnother: Boolean, other: Boolean): EffectStep {
        if (toUntapAnother) return EffectStep.SacrificeAlly(true, other)
        val ally = Selector.yours("allies", CardFilter.any(), 1)
        return sacrifice(if (other) ally.with(Selector.Self.EXCLUDE) else ally, true)
    }

    // ------------------------------------------------------------------ Choices that depend on the game or on other cards

    private fun choose(cards: Selector?): EffectStep {
        return act(Action.Select(cards, true, null, null))
    }

    private fun exactly(zones: String?, owner: Selector.Owner?, filter: CardFilter?, count: Amount?): Selector {
        return Selector(zones, owner, filter, count, count, Selector.Self.ANY, Selector.Mode.CHOOSE)
    }

    @JvmStatic
    fun chooseEnemyBy(count: Int, filter: EffectStep.FilterBy?): EffectStep {
        return chooseEnemyBy(count, filter, false)
    }

    @JvmStatic
    fun chooseEnemyBy(count: Int, filter: EffectStep.FilterBy?, upTo: Boolean): EffectStep {
        val enemies = Selector.enemies(filter!!.base, count).with(Selector.Relative.read(filter.level, filter.maxLevel))
        return choose(if (upTo) enemies.upTo() else enemies)
    }

    /** "elige todos los enemigos [filtro] en la línea frontal". */
    @JvmStatic
    fun targetAllEnemies(filter: CardFilter?): EffectStep {
        return choose(Selector.enemies(filter, 1).`as`(Selector.Mode.ALL).atLeast(0).upToAll())
    }

    @JvmStatic
    fun targetAllEnemies(): EffectStep {
        return targetAllEnemies(CardFilter.creatures())
    }

    // ------------------------------------------------------------------ Summoning and playing by effect

    /** "sin pagar su coste". */
    const val FREE = Action.Summon.FREE

    private const val ANYWHERE_BUT_FIELD = "hand,deck,tributeZone,discardPile,lifeStack"

    @JvmStatic
    fun summonFromZone(zones: String?, filter: CardFilter?, reduce: Int): EffectStep {
        return summonFromZone(zones, filter, reduce, false)
    }

    @JvmStatic
    fun summonFromZone(zones: String?, filter: CardFilter?, reduce: Int, toFront: Boolean): EffectStep {
        return act(Action.Summon(Selector.yours(zones, filter, 1), Amount.Fixed(reduce), toFront, true))
    }

    @JvmStatic
    fun summonSelf(zone: String?, reduce: Amount?, toFront: Boolean): EffectStep {
        return act(Action.Summon(Selector.self(zone), reduce, toFront, true))
    }

    // ------------------------------------------------------------------ Special summons: the creature, its summoner, its materials, and the summon

    private fun special(rule: SpecialRule): EffectStep {
        return act(Action.Sequence(frozenListOf(
                Action.Select(Selector.of(rule, Selector.Stage.CARD), false, EngineContext.SPECIAL_CARD, "Invocar"),
                Action.Select(Selector.of(rule, Selector.Stage.SUMMONER), false, EngineContext.SPECIAL_SUMMONER, "Invocador"),
                Action.Select(Selector.of(rule, Selector.Stage.MATERIAL), false, EngineContext.SPECIAL_MATERIALS, "Material"),
                Action.SummonSpecial(rule))))
    }

    /** "[1 aliado] puede invocar por [método] 1 criatura [filtro] de tu [zona] reduciendo el coste especial en N". */
    @JvmStatic
    fun specialFromZone(kind: String?, zone: String?, reduce: Int, summoner: String?, filter: CardFilter?): EffectStep {
        return special(SpecialRule(kind, zone, reduce, summoner, filter))
    }

    /** "otro aliado" (not this card) or "1 aliado" (any). */
    @JvmStatic
    fun specialFromZone(kind: String?, zone: String?, reduce: Int, otherAlly: Boolean): EffectStep {
        return specialFromZone(kind, zone, reduce, if (otherAlly) "other" else "any", CardFilter.any())
    }

    private fun ritual(rule: SpecialRule): EffectStep {
        return act(Action.Sequence(frozenListOf(
                Action.Select(Selector.of(rule, Selector.Stage.CARD), false, EngineContext.SPECIAL_CARD, "Invocar"),
                Action.Select(Selector.of(rule, Selector.Stage.SUMMONER), false, EngineContext.SPECIAL_SUMMONER, "Invocador"),
                Action.Select(Selector.of(rule, Selector.Stage.MATERIAL), false, EngineContext.SPECIAL_MATERIALS, "Sacrificar"),
                Action.SummonSpecial(rule))))
    }

    /** "[esta carta / un aliado] invoca por Ritual 1 criatura de tus [zona] reduciendo su coste especial en N". */
    @JvmStatic
    fun ritualFromZone(zone: String?, selfSummoner: Boolean, reduce: Int): EffectStep {
        return ritualFromZone(zone, selfSummoner, reduce, CardFilter.any())
    }

    @JvmStatic
    fun ritualFromZone(zone: String?, selfSummoner: Boolean, reduce: Int, filter: CardFilter?): EffectStep {
        return ritual(SpecialRule(SpecialRule.RITUAL, zone, reduce, if (selfSummoner) "self" else "any", filter))
    }

    // ------------------------------------------------------------------ Choosing an ally the next actions act on

    private fun pick(zones: String?, filter: CardFilter?, target: Boolean): EffectStep {
        return act(Action.Select(Selector.yours(zones, filter, 1), target, Memory.SUBJECT, "Elegido", true))
    }

    @JvmStatic
    fun pickAlly(): EffectStep {
        return pickAlly(false, false)
    }

    @JvmStatic
    fun pickAlly(attacker: Boolean): EffectStep {
        return pickAlly(attacker, false)
    }

    /** `attacker`: it is going to declare an attack; `target`: the text says "elige". */
    @JvmStatic
    fun pickAlly(attacker: Boolean, target: Boolean): EffectStep {
        return pick(if (attacker) "pickableAttackers" else "pickableAllies", CardFilter.any(), target)
    }

    @JvmStatic
    fun pickAllyWhere(filter: CardFilter?): EffectStep {
        return pickAllyWhere(filter, false)
    }

    @JvmStatic
    fun pickAllyWhere(filter: CardFilter?, target: Boolean): EffectStep {
        return pick("pickableAllies", filter, target)
    }

    @JvmStatic
    fun pickAttacker(filter: CardFilter?): EffectStep {
        return pickAttacker(filter, false)
    }

    @JvmStatic
    fun pickAttacker(filter: CardFilter?, target: Boolean): EffectStep {
        return pick("attackers", filter, target)
    }
}
