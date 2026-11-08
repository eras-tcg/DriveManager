package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.engine.effects.model.Action
import com.drive.drive_manager.simulator.engine.effects.model.Action.Move
import com.drive.drive_manager.simulator.engine.effects.model.Memory
import com.drive.drive_manager.simulator.engine.effects.model.Selector
import com.drive.drive_manager.simulator.engine.effects.model.SpecialRule

/**
 * The usual printed phrases, composed from basic actions, for [CardEffects] until the cards become data files (phase 7 of
 * the refactor). No logic here: each one only says which action and which selector.
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

    @JvmStatic
    fun tributeFromHandWhere(count: Int, filter: CardFilter?): EffectStep {
        return EffectStep.Do(Action.Move(hand(filter, count), Move.TRIBUTES, false, null, true))
    }

    /** "tributa hasta N cartas de tu mano". */
    @JvmStatic
    fun tributeFromHandUpTo(count: Int): EffectStep {
        return move(hand(CardFilter.any(), count).upTo(), Move.TRIBUTES)
    }

    @JvmStatic
    fun tributeFromZoneUpTo(zones: String?, max: Int, filter: CardFilter?): EffectStep {
        return move(Selector.yours(zones, filter, max).upTo(), Move.TRIBUTES)
    }

    /** "tributa N cartas del tope de tu mazo". */
    @JvmStatic
    fun tributeTop(count: Int): EffectStep {
        return move(Selector.yours("deck", CardFilter.any(), count).`as`(Selector.Mode.TOP), Move.TRIBUTES)
    }

    /** "tributa tu mano". */
    @JvmStatic
    fun tributeHand(): EffectStep {
        return move(Selector.all("hand", CardFilter.any()), Move.TRIBUTES)
    }

    /** "tributa [hasta] N materiales de esta carta": "hasta" still needs at least 1. */
    @JvmStatic
    fun tributeMaterials(count: Int, upTo: Boolean): EffectStep {
        val materials = Selector.yours("materials", CardFilter.any(), count)
        return move(if (upTo) materials.atLeast(1) else materials, Move.TRIBUTES)
    }

    @JvmStatic
    fun tributeMaterialWhere(filter: CardFilter?): EffectStep {
        return move(Selector.yours("materials", filter, 1), Move.TRIBUTES)
    }

    @JvmStatic
    fun tributeSubjectMaterials(count: Int): EffectStep {
        return move(Selector.yours("materials:subject", CardFilter.any(), count), Move.TRIBUTES)
    }

    @JvmStatic
    fun tributeSubjectMaterialsUpTo(max: Int): EffectStep {
        return move(Selector.yours("materials:subject", CardFilter.any(), max).atLeast(1), Move.TRIBUTES)
    }

    @JvmStatic
    fun tributeFromHandOrMaterials(count: Int): EffectStep {
        return move(Selector.yours("hand,materials", CardFilter.any(), count).with(Selector.Self.EXCLUDE), Move.TRIBUTES)
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

    // ------------------------------------------------------------------ To the Descarte

    @JvmStatic
    fun discardFromHand(count: Int): EffectStep {
        return move(hand(CardFilter.any(), count), Move.DISCARD)
    }

    @JvmStatic
    fun discardMaterials(count: Int): EffectStep {
        return move(Selector.yours("materials", CardFilter.any(), count), Move.DISCARD)
    }

    // ------------------------------------------------------------------ To the deck

    @JvmStatic
    fun sendFromZoneToDeckBottom(zones: String?, count: Int, filter: CardFilter?): EffectStep {
        return move(Selector.yours(zones, filter, count), Move.DECK_BOTTOM)
    }

    /** "envía hasta N cartas al fondo de tu mazo en orden aleatorio". */
    @JvmStatic
    fun sendFromZoneToDeckBottomUpTo(zones: String?, max: Int, filter: CardFilter?): EffectStep {
        return EffectStep.Do(Action.Move(Selector.yours(zones, filter, max).upTo(), Move.DECK_BOTTOM, true, null, false))
    }

    /** "envía N cartas de tus [zonas], incluida esta carta, al fondo de tu mazo". */
    @JvmStatic
    fun sendIncludingThisToDeckBottom(zones: String?, count: Int, filter: CardFilter?): EffectStep {
        return move(Selector.yours(zones, filter, count).with(Selector.Self.REQUIRE), Move.DECK_BOTTOM)
    }

    @JvmStatic
    fun handToDeckBottom(count: Int): EffectStep {
        return EffectStep.Do(Action.Move(hand(CardFilter.any(), count), Move.DECK_BOTTOM, false, null, true))
    }

    @JvmStatic
    fun toDeckTop(zone: String?, count: Int, upTo: Boolean): EffectStep {
        val cards = Selector.yours(zone, CardFilter.any(), count)
        return move(if (upTo) cards.upTo() else cards, Move.DECK_TOP)
    }

    // ------------------------------------------------------------------ To the hand

    @JvmStatic
    fun takeFromZone(zones: String?, filter: CardFilter?, count: Int, upTo: Boolean): EffectStep {
        val cards = Selector.yours(zones, filter, count)
        return EffectStep.Do(Action.Move(if (upTo) cards.upTo() else cards, Move.HAND, false, null, true))
    }

    /** "toma 1 carta [filtro] de tus [zona] por cada material tributado". */
    @JvmStatic
    fun takePerCount(zone: String?, filter: CardFilter?): EffectStep {
        return move(Selector(zone, Selector.Owner.YOU, filter, Amount.LastCount, Amount.LastCount, Selector.Self.ANY, Selector.Mode.CHOOSE), Move.HAND)
    }

    // ------------------------------------------------------------------ This card

    /** "envía esta carta a tu [zona]", from another zone than the field. */
    @JvmStatic
    fun sendSelfTo(zone: String?): EffectStep {
        return move(Selector.self("hand,tributeZone,discardPile,deck,lifeStack"), zone)
    }

    /** "envía esta carta al fondo de tu mazo", from the zone it is in. */
    @JvmStatic
    fun sendSelfToDeckBottom(): EffectStep {
        return move(Selector.self("hand,tributeZone,discardPile,lifeStack,field"), Move.DECK_BOTTOM)
    }

    /** "envía esta carta (del campo) a tu descarte": a permanent paying with itself. */
    @JvmStatic
    fun sendSelfFromFieldTo(zone: String?): EffectStep {
        return move(Selector.self("field"), zone)
    }

    @JvmStatic
    fun returnSelfToHand(): EffectStep {
        return move(Selector.self("field"), Move.HAND)
    }

    @JvmStatic
    fun placeSelf(zone: String?, toFront: Boolean, exhausted: Boolean): EffectStep {
        return EffectStep.Do(Action.Move(Selector.self(zone), if (toFront) Move.FRONT else Move.BACK, false, null, false, exhausted, false, null))
    }

    @JvmStatic
    fun placeSelfOnField(zone: String?): EffectStep {
        return placeSelf(zone, false, false)
    }

    @JvmStatic
    fun placeSelfInFrontLine(zone: String?, exhausted: Boolean): EffectStep {
        return placeSelf(zone, true, exhausted)
    }

    @JvmStatic
    fun moveSelfToFront(): EffectStep {
        return move(Selector.self("backLine"), Move.FRONT)
    }

    @JvmStatic
    fun selfToLife(): EffectStep {
        return EffectStep.Do(Action.Move(Selector.self("field"), Move.LIFE_TOP, false, null, false, false, true, null))
    }

    /** "coloca esta carta al tope de tus vidas [bocarriba]", from the zone it is in. */
    @JvmStatic
    fun selfToLifeFrom(faceUp: Boolean): EffectStep {
        return EffectStep.Do(Action.Move(Selector.self("hand,deck,tributeZone,discardPile,field"), Move.LIFE_TOP, false, null, false, false, !faceUp, null))
    }

    /** "añade esta carta como material a un aliado" (the ally chosen before). */
    @JvmStatic
    fun attachSelfToSubject(): EffectStep {
        return move(Selector.self("field,tributeZone"), "materials:subject")
    }

    @JvmStatic
    fun attachSelfAsResourceToSubject(): EffectStep {
        return move(Selector.self("hand,deck,tributeZone,discardPile,lifeStack,field"), "resources:subject")
    }

    // ------------------------------------------------------------------ Others of yours

    /** "tributa 1 [filtro] bajo tu control". */
    @JvmStatic
    fun tributeOwnCard(filter: CardFilter?): EffectStep {
        return move(Selector.yours("field", filter, 1).with(Selector.Self.EXCLUDE), Move.TRIBUTES)
    }

    @JvmStatic
    fun tributeOtherAllies(): EffectStep {
        return move(Selector.all("allies", CardFilter.any()).with(Selector.Self.EXCLUDE), Move.TRIBUTES)
    }

    /** "tribútalo": the card of the triggering event. */
    @JvmStatic
    fun tributeTriggerCard(): EffectStep {
        return move(Selector("event", Selector.Owner.YOU, CardFilter.any(), Amount.Fixed(1), Amount.Fixed(1), Selector.Self.ANY, Selector.Mode.ALL), Move.TRIBUTES)
    }

    @JvmStatic
    fun attachTriggerCardAsMaterial(): EffectStep {
        return move(Selector("event", Selector.Owner.YOU, CardFilter.any(), Amount.Fixed(1), Amount.Fixed(1), Selector.Self.ANY, Selector.Mode.ALL), "materials:self")
    }

    @JvmStatic
    fun returnOwnCardToHand(filter: CardFilter?): EffectStep {
        return move(Selector.yours("field", filter, 1).with(Selector.Self.EXCLUDE), Move.HAND)
    }

    /** "súbelo a tu mano": the ally chosen before. */
    @JvmStatic
    fun returnSubjectToHand(): EffectStep {
        return move(Selector.saved(Memory.SUBJECT), Move.HAND)
    }

    /** "súbelo con todos sus materiales": its materials first, since a creature leaving the field loses them. */
    @JvmStatic
    fun returnSubjectMaterialsToHand(): EffectStep {
        return move(Selector.all("materials:subject", CardFilter.any()), Move.HAND)
    }

    @JvmStatic
    fun placeFromZone(zones: String?, filter: CardFilter?, max: Int, toFront: Boolean, exhausted: Boolean): EffectStep {
        return EffectStep.Do(Action.Move(Selector.yours(zones, filter, max).upTo().with(Selector.Self.EXCLUDE), if (toFront) Move.FRONT else Move.BACK, false, null, false, exhausted, false, null))
    }

    /** "coloca [alguno de ellos] [agotado] en tu línea frontal": up to one of the last moved. */
    @JvmStatic
    fun placeLastTributed(filter: CardFilter?, toFront: Boolean, exhausted: Boolean): EffectStep {
        return EffectStep.Do(Action.Move(Selector.yours("saved:" + Memory.LAST, filter, 1).upTo(), if (toFront) Move.FRONT else Move.BACK, false, null, false, exhausted, false, null))
    }

    @JvmStatic
    fun moveAllyToFront(): EffectStep {
        return move(Selector.yours("backLine", CardFilter.creatures(), 1).upTo(), Move.FRONT)
    }

    // ------------------------------------------------------------------ Materials and resources

    @JvmStatic
    fun materialToFrontLine(filter: CardFilter?): EffectStep {
        return move(Selector.yours("materials", filter!!.asCreature(), 1), Move.FRONT)
    }

    @JvmStatic
    fun materialToBack(filter: CardFilter?): EffectStep {
        return move(Selector.yours("materials", filter!!.asCreature(), 1), Move.BACK)
    }

    /** "coloca hasta 1 [filtro] de los materiales de esta carta en tu línea frontal". */
    @JvmStatic
    fun materialUpToToFront(filter: CardFilter?): EffectStep {
        return move(Selector.yours("materials", filter, 1).upTo(), Move.FRONT)
    }

    /** "añádele todos los materiales de esta carta": to the card just moved. */
    @JvmStatic
    fun materialsToLast(): EffectStep {
        return move(Selector.all("materials", CardFilter.any()), "materials:last")
    }

    /** "sube los materiales de esta carta a tu mano"; `upTo`: those the player picks. */
    @JvmStatic
    fun materialsToHand(upTo: Boolean): EffectStep {
        return move(if (upTo) Selector("materials", Selector.Owner.YOU, CardFilter.any(), Amount.Fixed(0), Amount.Fixed(Int.MAX_VALUE), Selector.Self.ANY, Selector.Mode.CHOOSE) else Selector.all("materials", CardFilter.any()), Move.HAND)
    }

    @JvmStatic
    fun addResourceFrom(zones: String?, filter: CardFilter?): EffectStep {
        return addResourceFromZone(zones, filter, false)
    }

    @JvmStatic
    fun addResourceFromZone(zones: String?, filter: CardFilter?, upTo: Boolean): EffectStep {
        val cards = Selector.yours(zones, filter, 1).with(Selector.Self.EXCLUDE)
        return EffectStep.Do(Action.Move(if (upTo) cards.upTo() else cards, "resources:self", false, null, !upTo))
    }

    @JvmStatic
    fun addAllyAsResource(filter: CardFilter?): EffectStep {
        return move(Selector.yours("allies", filter, 1).with(Selector.Self.EXCLUDE), "resources:self")
    }

    // ------------------------------------------------------------------ Lives

    @JvmStatic
    fun deckTopToLife(): EffectStep {
        return EffectStep.Do(Action.Move(Selector.yours("deck", CardFilter.any(), 1).`as`(Selector.Mode.TOP), Move.LIFE_TOP, false, null, false, false, true, null))
    }

    @JvmStatic
    fun materialToLife(): EffectStep {
        return move(Selector.yours("materials", CardFilter.any(), 1).upTo(), Move.LIFE_TOP)
    }

    @JvmStatic
    fun zoneToLife(zones: String?, filter: CardFilter?, faceUp: Boolean): EffectStep {
        return EffectStep.Do(Action.Move(Selector.yours(zones, filter, 1).upTo().with(Selector.Self.EXCLUDE), Move.LIFE_TOP, false, null, false, false, !faceUp, null))
    }

    // ------------------------------------------------------------------ Looked cards: one choice, the rest ordered in it

    private fun fromLooked(filter: CardFilter?, min: Int, max: Int, to: String?, rest: String?, faceDown: Boolean): EffectStep {
        val looked = Selector("looked", Selector.Owner.YOU, filter, Amount.Fixed(min), Amount.Fixed(max), Selector.Self.ANY, Selector.Mode.CHOOSE)
        return EffectStep.Do(Action.Move(looked, to, false, null, false, false, faceDown, rest))
    }

    /** "de ellas toma hasta N [filtro]. Envía el resto al fondo de tu mazo". */
    @JvmStatic
    fun takeFromLookedRestToBottom(max: Int, filter: CardFilter?): EffectStep {
        return fromLooked(filter, 0, max, Move.HAND, Move.DECK_BOTTOM, false)
    }

    /** "de ellas toma hasta N [filtro]" leaving the rest with the effect. */
    @JvmStatic
    fun takeFromLooked(max: Int, filter: CardFilter?): EffectStep {
        return fromLooked(filter, 0, max, Move.HAND, null, false)
    }

    @JvmStatic
    fun tributeFromLooked(max: Int, filter: CardFilter?): EffectStep {
        return fromLooked(filter, 0, max, Move.TRIBUTES, null, false)
    }

    @JvmStatic
    fun tributeFromLookedRestToBottom(count: Int): EffectStep {
        return fromLooked(CardFilter.any(), count, count, Move.TRIBUTES, Move.DECK_BOTTOM, false)
    }

    @JvmStatic
    fun discardFromLookedRestToBottom(max: Int): EffectStep {
        return fromLooked(CardFilter.any(), 0, max, Move.DISCARD, Move.DECK_BOTTOM, false)
    }

    /** "de ellas descarta al menos N bocabajo. Coloca el resto al tope de tu mazo en cualquier orden". */
    @JvmStatic
    fun discardLookedRestTop(atLeast: Int): EffectStep {
        return fromLooked(CardFilter.any(), atLeast, Int.MAX_VALUE, Move.DISCARD, Move.DECK_TOP, true)
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
    fun drawLastCount(): EffectStep {
        return act(Action.Draw(Amount.LastCount, false))
    }

    @JvmStatic
    fun eachPlayerDraws(count: Int): EffectStep {
        return act(Action.Draw(Amount.Fixed(count), true))
    }

    @JvmStatic
    fun gainGems(count: Int): EffectStep {
        return act(Action.Gems(Amount.Fixed(count)))
    }

    @JvmStatic
    fun damageByEffect(amount: Int): EffectStep {
        return act(Action.Damage(Amount.Fixed(amount)))
    }

    @JvmStatic
    fun damageBySelfStrength(): EffectStep {
        return act(Action.Damage(Amount.SourceStrength))
    }

    @JvmStatic
    fun lookTop(count: Int): EffectStep {
        return act(Action.Look(Amount.Fixed(count)))
    }

    @JvmStatic
    fun lookTopBy(count: Amount?): EffectStep {
        return act(Action.Look(count))
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
    fun tapChosen(): EffectStep {
        return setTapped(TARGETS, true, false)
    }

    @JvmStatic
    fun tapChosenOnField(): EffectStep {
        return setTapped(TARGETS, true, false)
    }

    @JvmStatic
    fun tapSummoned(): EffectStep {
        return setTapped(SUMMONED, true, false)
    }

    /** "agota dicho enemigo": the card of the triggering event. */
    @JvmStatic
    fun tapTriggerCard(): EffectStep {
        return setTapped(Selector.all("eventOnField", CardFilter.any()), true, false)
    }

    @JvmStatic
    fun tapOwnCard(filter: CardFilter?): EffectStep {
        return setTapped(Selector.yours("field", filter, 1).with(Selector.Self.EXCLUDE), true, true)
    }

    @JvmStatic
    fun untapSelf(): EffectStep {
        return setTapped(Selector.self("field"), false, false)
    }

    @JvmStatic
    fun untapSubject(): EffectStep {
        return setTapped(SUBJECT_OR_SELF, false, false)
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

    @JvmStatic
    fun untapOwnCard(filter: CardFilter?): EffectStep {
        return setTapped(Selector.yours("field", filter, 1), false, false)
    }

    @JvmStatic
    fun untapStructure(filter: CardFilter?): EffectStep {
        return setTapped(Selector.yours("field", filter!!.asStructure(), 1), false, true)
    }

    @JvmStatic
    fun untapStructures(max: Int, filter: CardFilter?): EffectStep {
        return setTapped(Selector.yours("field", filter!!.asStructure(), max).upTo(), false, false)
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

    @JvmStatic
    fun gainStrengthUntil(amount: Int, until: String?): EffectStep {
        return strength(SUBJECT_OR_SELF, Amount.Fixed(amount), until(until))
    }

    @JvmStatic
    fun gainStrengthBy(amount: Amount?): EffectStep {
        return strength(SUBJECT_OR_SELF, amount, Action.Until.TURN)
    }

    @JvmStatic
    fun gainStrengthPerCount(each: Int): EffectStep {
        return strength(SUBJECT_OR_SELF, Amount.Times(Amount.LastCount, each), Action.Until.TURN)
    }

    @JvmStatic
    fun gainUntilEndOfAttack(keyword: String?): EffectStep {
        return keyword(SUBJECT_OR_SELF, keyword, Action.Until.ATTACK)
    }

    @JvmStatic
    fun gainUntilEndOfTurn(keyword: String?): EffectStep {
        return keyword(SUBJECT_OR_SELF, keyword, Action.Until.TURN)
    }

    @JvmStatic
    fun gainUntilNextTurn(keyword: String?): EffectStep {
        return keyword(SUBJECT_OR_SELF, keyword, Action.Until.NEXT_TURN)
    }

    @JvmStatic
    fun gainUntilNextAttack(strength: Int, keyword: String?): EffectStep {
        return act(Action.Modify(SUBJECT_OR_SELF, if (strength == 0) null else Amount.Fixed(strength), false, keyword, Action.Until.NEXT_ATTACK))
    }

    @JvmStatic
    fun reduceChosen(amount: Int, untilNextTurn: Boolean): EffectStep {
        return strength(TARGETS, Amount.Fixed(-amount), if (untilNextTurn) Action.Until.NEXT_TURN else Action.Until.TURN)
    }

    @JvmStatic
    fun reduceChosenBy(amount: Amount?): EffectStep {
        return strength(TARGETS, Amount.Times(amount, -1), Action.Until.TURN)
    }

    @JvmStatic
    fun reduceChosenPerCount(each: Int): EffectStep {
        return strength(TARGETS, Amount.Times(Amount.LastCount, -each), Action.Until.TURN)
    }

    @JvmStatic
    fun reduceChosenToZero(): EffectStep {
        return act(Action.Modify(TARGETS, null, true, null, Action.Until.TURN))
    }

    @JvmStatic
    fun reduceChosenUntilAttack(amount: Int): EffectStep {
        return strength(TARGETS, Amount.Fixed(-amount), Action.Until.ATTACK)
    }

    @JvmStatic
    fun chosenCannotBeExhausted(until: String?): EffectStep {
        return keyword(TARGETS, EffectEngine.CANNOT_TAP, until(until))
    }

    @JvmStatic
    fun chosenCannotBlock(): EffectStep {
        return keyword(TARGETS, EffectEngine.NO_BLOCK, Action.Until.TURN)
    }

    @JvmStatic
    fun summonedCannotAttack(): EffectStep {
        return keyword(SUMMONED, EffectEngine.NO_ATTACK, Action.Until.TURN)
    }

    // ------------------------------------------------------------------ Destroying and sacrificing

    private fun destroy(cards: Selector?, required: Boolean): EffectStep {
        return act(Action.Destroy(cards, false, required))
    }

    private fun sacrifice(cards: Selector?, required: Boolean): EffectStep {
        return act(Action.Destroy(cards, true, required))
    }

    @JvmStatic
    fun destroyChosen(): EffectStep {
        return destroy(TARGETS, false)
    }

    @JvmStatic
    fun destroySelf(): EffectStep {
        return destroy(Selector.self("field"), false)
    }

    @JvmStatic
    fun destroyAlly(filter: CardFilter?): EffectStep {
        return destroy(Selector.yours("allies", filter, 1), true)
    }

    /** "destruye 1 enemigo": without "elige" it is not a target (designer ruling 2026-11-01). */
    @JvmStatic
    fun destroyEnemy(count: Int, filter: CardFilter?): EffectStep {
        return destroy(Selector.enemies(filter, count), true)
    }

    @JvmStatic
    fun destroyEnemyNoTarget(): EffectStep {
        return destroy(Selector.enemies(CardFilter.creatures(), 1), false)
    }

    @JvmStatic
    fun sacrificeSelf(): EffectStep {
        return sacrifice(Selector.self("field"), false)
    }

    @JvmStatic
    fun sacrificeChosen(): EffectStep {
        return sacrifice(TARGETS, false)
    }

    @JvmStatic
    fun sacrificeAllyWhere(filter: CardFilter?): EffectStep {
        return sacrifice(Selector.yours("allies", filter, 1), true)
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

    /** "por cada material tributado elige 1 enemigo distinto". */
    @JvmStatic
    fun chooseEnemiesPerCount(filter: CardFilter?): EffectStep {
        return choose(exactly("frontLine", Selector.Owner.OPPONENT, filter, Amount.LastCount))
    }

    @JvmStatic
    fun chooseEnemiesPerCountEvery(filter: CardFilter?, per: Int): EffectStep {
        return choose(exactly("frontLine", Selector.Owner.OPPONENT, filter, Amount.Scaled(Amount.LastCount, per, 0)))
    }

    /** "elige 1 enemigo de nivel igual o menor [que el aliado elegido]". */
    @JvmStatic
    fun chooseEnemyUpToSubjectLevel(lower: Boolean): EffectStep {
        return choose(Selector.enemies(CardFilter.creatures(), 1).with(Selector.Relative.level("subject", if (lower) Selector.Compare.LESS else Selector.Compare.AT_MOST, 0)))
    }

    @JvmStatic
    fun chooseEnemyUpToSummonUsed(): EffectStep {
        return choose(Selector.enemies(CardFilter.creatures(), 1).with(Selector.Relative.read(null, Amount.UsedToSummon)))
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

    /** "elige 1 criatura [filtro] en el campo": yours or an enemy's, by the normal rules (designer ruling 2026-10-29). */
    @JvmStatic
    fun chooseCreatureOnField(filter: CardFilter?, upTo: Boolean): EffectStep {
        val creatures = exactly("field", Selector.Owner.BOTH, filter!!.asCreature(), Amount.Fixed(1)).with(Selector.Self.EXCLUDE)
        return act(Action.Select(if (upTo) creatures.upTo() else creatures, true, null, "Elegir"))
    }

    @JvmStatic
    fun chooseFrontLineCreatures(count: Int, filter: CardFilter?): EffectStep {
        return choose(exactly("frontLine", Selector.Owner.BOTH, filter!!.asCreature(), Amount.Fixed(count)))
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

    // ------------------------------------------------------------------ More phrases

    @JvmStatic
    fun tributeFromHandAsSubjectLevel(): EffectStep {
        return move(exactly("hand", Selector.Owner.YOU, CardFilter.any(), Amount.SubjectLevel).with(Selector.Self.EXCLUDE), Move.TRIBUTES)
    }

    /** "envía cartas de tu mano al fondo de tu mazo hasta tener N cartas en tu mano". */
    @JvmStatic
    fun handDownTo(keep: Int): EffectStep {
        return move(exactly("hand", Selector.Owner.YOU, CardFilter.any(), Amount.HandOver(keep)), Move.DECK_BOTTOM)
    }

    @JvmStatic
    fun tributeChosen(): EffectStep {
        return move(Selector.saved(Memory.TARGETS), Move.TRIBUTES)
    }

    @JvmStatic
    fun tributeTopLife(): EffectStep {
        return move(Selector.yours("lifeStack", CardFilter.any(), 1).`as`(Selector.Mode.TOP), Move.TRIBUTES)
    }

    /** "Si es [filtro]: muévela a tu línea frontal": the creature this effect summoned. */
    @JvmStatic
    fun moveSummonedToFrontIf(filter: CardFilter?): EffectStep {
        return move(Selector("saved:" + Memory.SUMMONED, Selector.Owner.YOU, filter, Amount.Fixed(0), Amount.Fixed(1), Selector.Self.ANY, Selector.Mode.ALL), Move.FRONT)
    }

    /** "envía N copias de esta carta en tu retaguardia a tu [zona]" (this card included). */
    @JvmStatic
    fun sendCopiesFromField(count: Int, zone: String?): EffectStep {
        return move(Selector.yours("backLine", CardFilter.any(), count).with(Selector.Relative.copiesOf("self")), zone)
    }

    /** "agota hasta tantas copias de esta carta como controles". */
    @JvmStatic
    fun tapCopiesUpTo(): EffectStep {
        return setTapped(Selector("field", Selector.Owner.YOU, CardFilter.any(), Amount.Fixed(1), Amount.Fixed(Int.MAX_VALUE), Selector.Self.ANY, Selector.Mode.CHOOSE)
                .with(Selector.Relative.copiesOf("self")), true, false)
    }

    @JvmStatic
    fun tapSubject(): EffectStep {
        return setTapped(Selector.all("subjectOrSummoned", CardFilter.any()), true, false)
    }

    @JvmStatic
    fun untapAllySameLevelAsLast(): EffectStep {
        return setTapped(Selector.yours("allies", CardFilter.any(), 1).with(Selector.Relative.level("last", Selector.Compare.EQUAL, 0)), false, false)
    }

    @JvmStatic
    fun sacrificeAllyWeakerThanSelf(): EffectStep {
        return sacrifice(Selector.yours("allies", CardFilter.any(), 1).with(Selector.Self.EXCLUDE).with(Selector.Relative.weakerThan("self")), true)
    }

    @JvmStatic
    fun sacrificeAllInFrontLine(filter: CardFilter?): EffectStep {
        return sacrifice(Selector("frontLine", Selector.Owner.BOTH, filter!!.asCreature(), Amount.Fixed(0), Amount.Fixed(Int.MAX_VALUE), Selector.Self.ANY, Selector.Mode.ALL), false)
    }

    /** "sacrifica ambas criaturas": the enemy it clashes with and this card. */
    @JvmStatic
    fun sacrificeClashing(): EffectStep {
        return sacrifice(Selector("clash,thisCard", Selector.Owner.YOU, CardFilter.any(), Amount.Fixed(0), Amount.Fixed(2), Selector.Self.ANY, Selector.Mode.ALL), false)
    }

    /** "reduce su fuerza en X" on the creature this card clashes with. */
    @JvmStatic
    fun reduceClashOpponent(amount: Amount?): EffectStep {
        return strength(Selector.all("clash", CardFilter.any()), Amount.Times(amount, -1), Action.Until.TURN)
    }

    /** "reducirla a 0": the enemy whose strength was reduced in the triggering event. */
    @JvmStatic
    fun reduceTriggerToZero(): EffectStep {
        return act(Action.Modify(Selector.all("eventOnField", CardFilter.any()), null, true, null, Action.Until.TURN))
    }

    @JvmStatic
    fun reduceTriggerFurther(amount: Int): EffectStep {
        return strength(Selector.all("eventOnField", CardFilter.any()), Amount.Fixed(-amount), Action.Until.AS_EVENT)
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
    fun summonFromZoneBy(zones: String?, filter: EffectStep.FilterBy?, reduce: Int): EffectStep {
        return act(Action.Summon(Selector.yours(zones, filter!!.base, 1).with(Selector.Relative.read(filter.level, filter.maxLevel)), Amount.Fixed(reduce), false, true))
    }

    @JvmStatic
    fun summonSelf(zone: String?, reduce: Amount?, toFront: Boolean): EffectStep {
        return act(Action.Summon(Selector.self(zone), reduce, toFront, true))
    }

    @JvmStatic
    fun summonSelfFree(): EffectStep {
        return act(Action.Summon(Selector.self(ANYWHERE_BUT_FIELD), Amount.Fixed(FREE), false, true))
    }

    @JvmStatic
    fun summonSelfFromZone(zone: String?, reduce: Int): EffectStep {
        return act(Action.Summon(Selector.self(zone), Amount.Fixed(reduce), false, true))
    }

    /** "reduciendo su coste en 1 por cada N cartas enviadas". */
    @JvmStatic
    fun summonSelfPerCount(zone: String?, per: Int): EffectStep {
        return act(Action.Summon(Selector.self(zone), Amount.Scaled(Amount.LastCount, per, 0), false, false))
    }

    @JvmStatic
    fun summonUpTo(zones: String?, filter: CardFilter?, max: Int, reduce: Int): EffectStep {
        return act(Action.Summon(Selector.yours(zones, filter, max).upTo(), Amount.Fixed(reduce), false, false))
    }

    /** "invoca 1 de los recursos de esta carta": none is fine. */
    @JvmStatic
    fun summonResource(reduce: Int): EffectStep {
        return act(Action.Summon(Selector.yours("resources", CardFilter.creatures(), 1).upTo(), Amount.Fixed(reduce), false, true))
    }

    /** "de nivel igual a la cantidad de [copias agotadas]", free. */
    @JvmStatic
    fun summonLevelLastCount(zones: String?, filter: CardFilter?): EffectStep {
        return act(Action.Summon(Selector.yours(zones, filter, 1).with(Selector.Relative.read(Amount.LastCount, null)), Amount.Fixed(FREE), false, true))
    }

    /** "de nivel menor [que el aliado destruido]" ("lower"), or "de nivel igual o menor, pero distinto nombre". */
    @JvmStatic
    fun summonRelativeToLast(zones: String?, filter: CardFilter?, reduce: Int, toFront: Boolean, relation: String?): EffectStep {
        val relative = if ("lower" == relation) Selector.Relative.level("last", Selector.Compare.LESS, 0) else Selector.Relative.upToLevelOtherName("last")
        return act(Action.Summon(Selector.yours(zones, filter, 1).with(relative), Amount.Fixed(reduce), toFront, true))
    }

    @JvmStatic
    fun playFromZone(zones: String?, filter: CardFilter?, reduce: Int): EffectStep {
        return act(Action.Play(Selector.yours(zones, filter, 1), Amount.Fixed(reduce), true))
    }

    /** "reduciendo su coste en N por cada carta tributada". */
    @JvmStatic
    fun playFromZoneByCount(zones: String?, filter: CardFilter?, each: Int): EffectStep {
        return act(Action.Play(Selector.yours(zones, filter, 1), Amount.Times(Amount.LastCount, each), true))
    }

    @JvmStatic
    fun playSelfFree(): EffectStep {
        return act(Action.Play(Selector.self("hand"), Amount.Fixed(FREE), true))
    }

    @JvmStatic
    fun playSelfFromZone(zone: String?, reduceBySourceLevel: Boolean): EffectStep {
        return act(Action.Play(Selector.self(zone), if (reduceBySourceLevel) Amount.TriggerSourceLevel else Amount.Fixed(0), true))
    }

    @JvmStatic
    fun playSelfReduced(zone: String?, reduce: Int): EffectStep {
        return act(Action.Play(Selector.self(zone), Amount.Fixed(reduce), true))
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

    /** "un aliado invoca esta carta de forma especial [reduciendo su coste especial en N]". */
    @JvmStatic
    fun specialSelf(zone: String?, reduce: Int): EffectStep {
        return special(SpecialRule(SpecialRule.OF_THIS_CARD, zone, reduce, "any", CardFilter.any()))
    }

    /** "reduciendo el coste especial en X donde X es el nivel del aliado elegido". */
    @JvmStatic
    fun specialBySubjectLevel(kind: String?, zone: String?, filter: CardFilter?): EffectStep {
        return special(SpecialRule(kind, zone, 0, "subject", filter, Amount.SubjectLevel, null))
    }

    /** "reduciendo el coste especial en 1 por cada … tributado". */
    @JvmStatic
    fun specialByCount(kind: String?, zone: String?, summoner: String?, filter: CardFilter?): EffectStep {
        return special(SpecialRule(kind, zone, 0, summoner, filter, Amount.LastCount, null))
    }

    /** "del mismo nivel, pero distinto nombre [que la carta devuelta]". */
    @JvmStatic
    fun specialRelativeToLast(zone: String?, filter: CardFilter?, reduce: Int): EffectStep {
        return special(SpecialRule("any", zone, reduce, "any", filter, null, Selector.Relative.sameLevelOtherName("last")))
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

    @JvmStatic
    fun ritualFromZoneBy(zone: String?, selfSummoner: Boolean, reduce: Amount?, filter: CardFilter?): EffectStep {
        return ritual(SpecialRule(SpecialRule.RITUAL, zone, 0, if (selfSummoner) "self" else "any", filter, reduce, null))
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
