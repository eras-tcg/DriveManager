package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.engine.effects.model.Action;
import com.drive.drive_manager.simulator.engine.effects.model.Action.Move;
import com.drive.drive_manager.simulator.engine.effects.model.Memory;
import com.drive.drive_manager.simulator.engine.effects.model.Selector;
import com.drive.drive_manager.simulator.engine.effects.model.SpecialRule;

import java.util.List;

/**
 * The usual printed phrases, composed from basic actions, for {@link CardEffects} until the cards become data files (phase 7 of
 * the refactor). No logic here: each one only says which action and which selector.
 */
final class Compose {

    private Compose() {}

    private static EffectStep move(Selector cards, String to) {
        return new EffectStep.Do(new Action.Move(cards, to, false, null, false));
    }

    private static Selector hand(CardFilter filter, int count) {
        return Selector.yours("hand", filter, count).with(Selector.Self.EXCLUDE);
    }

    // ------------------------------------------------------------------ Choosing enemies

    /** "elige N enemigos [filtro]": the literal word makes them targets (designer ruling 2026-11-01). */
    static EffectStep chooseEnemy(int count, CardFilter filter) {
        return chooseEnemy(count, filter, false);
    }

    static EffectStep chooseEnemy(int count, CardFilter filter, boolean upTo) {
        Selector enemies = Selector.enemies(filter, count);
        return new EffectStep.Do(new Action.Select(upTo ? enemies.upTo() : enemies, true, null, null));
    }

    // ------------------------------------------------------------------ To the Tributos

    /** "tributa N cartas de tu mano" (not the effect's own card when it is in the hand). */
    static EffectStep tributeFromHand(int count) {
        return move(hand(CardFilter.any(), count), Move.TRIBUTES);
    }

    static EffectStep tributeFromHandWhere(int count, CardFilter filter) {
        return new EffectStep.Do(new Action.Move(hand(filter, count), Move.TRIBUTES, false, null, true));
    }

    /** "tributa hasta N cartas de tu mano". */
    static EffectStep tributeFromHandUpTo(int count) {
        return move(hand(CardFilter.any(), count).upTo(), Move.TRIBUTES);
    }

    static EffectStep tributeFromZoneUpTo(String zones, int max, CardFilter filter) {
        return move(Selector.yours(zones, filter, max).upTo(), Move.TRIBUTES);
    }

    /** "tributa N cartas del tope de tu mazo". */
    static EffectStep tributeTop(int count) {
        return move(Selector.yours("deck", CardFilter.any(), count).as(Selector.Mode.TOP), Move.TRIBUTES);
    }

    /** "tributa tu mano". */
    static EffectStep tributeHand() {
        return move(Selector.all("hand", CardFilter.any()), Move.TRIBUTES);
    }

    /** "tributa [hasta] N materiales de esta carta": "hasta" still needs at least 1. */
    static EffectStep tributeMaterials(int count, boolean upTo) {
        Selector materials = Selector.yours("materials", CardFilter.any(), count);
        return move(upTo ? materials.atLeast(1) : materials, Move.TRIBUTES);
    }

    static EffectStep tributeMaterialWhere(CardFilter filter) {
        return move(Selector.yours("materials", filter, 1), Move.TRIBUTES);
    }

    static EffectStep tributeSubjectMaterials(int count) {
        return move(Selector.yours("materials:subject", CardFilter.any(), count), Move.TRIBUTES);
    }

    static EffectStep tributeSubjectMaterialsUpTo(int max) {
        return move(Selector.yours("materials:subject", CardFilter.any(), max).atLeast(1), Move.TRIBUTES);
    }

    static EffectStep tributeFromHandOrMaterials(int count) {
        return move(Selector.yours("hand,materials", CardFilter.any(), count).with(Selector.Self.EXCLUDE), Move.TRIBUTES);
    }

    /** "tributa "X" de los recursos de esta carta". */
    static EffectStep tributeResource(String name) {
        return move(Selector.yours("resources", CardFilter.any().named(name), 1).as(Selector.Mode.TOP), Move.TRIBUTES);
    }

    static EffectStep tributeResources(int count, CardFilter filter) {
        return move(Selector.yours("resources", filter, count), Move.TRIBUTES);
    }

    // ------------------------------------------------------------------ To the Descarte

    static EffectStep discardFromHand(int count) {
        return move(hand(CardFilter.any(), count), Move.DISCARD);
    }

    static EffectStep discardMaterials(int count) {
        return move(Selector.yours("materials", CardFilter.any(), count), Move.DISCARD);
    }

    // ------------------------------------------------------------------ To the deck

    static EffectStep sendFromZoneToDeckBottom(String zones, int count, CardFilter filter) {
        return move(Selector.yours(zones, filter, count), Move.DECK_BOTTOM);
    }

    /** "envía hasta N cartas al fondo de tu mazo en orden aleatorio". */
    static EffectStep sendFromZoneToDeckBottomUpTo(String zones, int max, CardFilter filter) {
        return new EffectStep.Do(new Action.Move(Selector.yours(zones, filter, max).upTo(), Move.DECK_BOTTOM, true, null, false));
    }

    /** "envía N cartas de tus [zonas], incluida esta carta, al fondo de tu mazo". */
    static EffectStep sendIncludingThisToDeckBottom(String zones, int count, CardFilter filter) {
        return move(Selector.yours(zones, filter, count).with(Selector.Self.REQUIRE), Move.DECK_BOTTOM);
    }

    static EffectStep handToDeckBottom(int count) {
        return new EffectStep.Do(new Action.Move(hand(CardFilter.any(), count), Move.DECK_BOTTOM, false, null, true));
    }

    static EffectStep toDeckTop(String zone, int count, boolean upTo) {
        Selector cards = Selector.yours(zone, CardFilter.any(), count);
        return move(upTo ? cards.upTo() : cards, Move.DECK_TOP);
    }

    // ------------------------------------------------------------------ To the hand

    static EffectStep takeFromZone(String zones, CardFilter filter, int count, boolean upTo) {
        Selector cards = Selector.yours(zones, filter, count);
        return new EffectStep.Do(new Action.Move(upTo ? cards.upTo() : cards, Move.HAND, false, null, true));
    }

    /** "toma 1 carta [filtro] de tus [zona] por cada material tributado". */
    static EffectStep takePerCount(String zone, CardFilter filter) {
        return move(new Selector(zone, Selector.Owner.YOU, filter, Amount.LastCount.INSTANCE, Amount.LastCount.INSTANCE, Selector.Self.ANY, Selector.Mode.CHOOSE), Move.HAND);
    }

    // ------------------------------------------------------------------ This card

    /** "envía esta carta a tu [zona]", from another zone than the field. */
    static EffectStep sendSelfTo(String zone) {
        return move(Selector.self("hand,tributeZone,discardPile,deck,lifeStack"), zone);
    }

    /** "envía esta carta al fondo de tu mazo", from the zone it is in. */
    static EffectStep sendSelfToDeckBottom() {
        return move(Selector.self("hand,tributeZone,discardPile,lifeStack,field"), Move.DECK_BOTTOM);
    }

    /** "envía esta carta (del campo) a tu descarte": a permanent paying with itself. */
    static EffectStep sendSelfFromFieldTo(String zone) {
        return move(Selector.self("field"), zone);
    }

    static EffectStep returnSelfToHand() {
        return move(Selector.self("field"), Move.HAND);
    }

    static EffectStep placeSelf(String zone, boolean toFront, boolean exhausted) {
        return new EffectStep.Do(new Action.Move(Selector.self(zone), toFront ? Move.FRONT : Move.BACK, false, null, false, exhausted, false, null));
    }

    static EffectStep placeSelfOnField(String zone) {
        return placeSelf(zone, false, false);
    }

    static EffectStep placeSelfInFrontLine(String zone, boolean exhausted) {
        return placeSelf(zone, true, exhausted);
    }

    static EffectStep moveSelfToFront() {
        return move(Selector.self("backLine"), Move.FRONT);
    }

    static EffectStep selfToLife() {
        return new EffectStep.Do(new Action.Move(Selector.self("field"), Move.LIFE_TOP, false, null, false, false, true, null));
    }

    /** "coloca esta carta al tope de tus vidas [bocarriba]", from the zone it is in. */
    static EffectStep selfToLifeFrom(boolean faceUp) {
        return new EffectStep.Do(new Action.Move(Selector.self("hand,deck,tributeZone,discardPile,field"), Move.LIFE_TOP, false, null, false, false, !faceUp, null));
    }

    /** "añade esta carta como material a un aliado" (the ally chosen before). */
    static EffectStep attachSelfToSubject() {
        return move(Selector.self("field,tributeZone"), "materials:subject");
    }

    static EffectStep attachSelfAsResourceToSubject() {
        return move(Selector.self("hand,deck,tributeZone,discardPile,lifeStack,field"), "resources:subject");
    }

    // ------------------------------------------------------------------ Others of yours

    /** "tributa 1 [filtro] bajo tu control". */
    static EffectStep tributeOwnCard(CardFilter filter) {
        return move(Selector.yours("field", filter, 1).with(Selector.Self.EXCLUDE), Move.TRIBUTES);
    }

    static EffectStep tributeOtherAllies() {
        return move(Selector.all("allies", CardFilter.any()).with(Selector.Self.EXCLUDE), Move.TRIBUTES);
    }

    /** "tribútalo": the card of the triggering event. */
    static EffectStep tributeTriggerCard() {
        return move(new Selector("event", Selector.Owner.YOU, CardFilter.any(), new Amount.Fixed(1), new Amount.Fixed(1), Selector.Self.ANY, Selector.Mode.ALL), Move.TRIBUTES);
    }

    static EffectStep attachTriggerCardAsMaterial() {
        return move(new Selector("event", Selector.Owner.YOU, CardFilter.any(), new Amount.Fixed(1), new Amount.Fixed(1), Selector.Self.ANY, Selector.Mode.ALL), "materials:self");
    }

    static EffectStep returnOwnCardToHand(CardFilter filter) {
        return move(Selector.yours("field", filter, 1).with(Selector.Self.EXCLUDE), Move.HAND);
    }

    /** "súbelo a tu mano": the ally chosen before. */
    static EffectStep returnSubjectToHand() {
        return move(Selector.saved(Memory.SUBJECT), Move.HAND);
    }

    /** "súbelo con todos sus materiales": its materials first, since a creature leaving the field loses them. */
    static EffectStep returnSubjectMaterialsToHand() {
        return move(Selector.all("materials:subject", CardFilter.any()), Move.HAND);
    }

    static EffectStep placeFromZone(String zones, CardFilter filter, int max, boolean toFront, boolean exhausted) {
        return new EffectStep.Do(new Action.Move(Selector.yours(zones, filter, max).upTo().with(Selector.Self.EXCLUDE), toFront ? Move.FRONT : Move.BACK, false, null, false, exhausted, false, null));
    }

    /** "coloca [alguno de ellos] [agotado] en tu línea frontal": up to one of the last moved. */
    static EffectStep placeLastTributed(CardFilter filter, boolean toFront, boolean exhausted) {
        return new EffectStep.Do(new Action.Move(Selector.yours("saved:" + Memory.LAST, filter, 1).upTo(), toFront ? Move.FRONT : Move.BACK, false, null, false, exhausted, false, null));
    }

    static EffectStep moveAllyToFront() {
        return move(Selector.yours("backLine", CardFilter.creatures(), 1).upTo(), Move.FRONT);
    }

    // ------------------------------------------------------------------ Materials and resources

    static EffectStep materialToFrontLine(CardFilter filter) {
        return move(Selector.yours("materials", filter.asCreature(), 1), Move.FRONT);
    }

    static EffectStep materialToBack(CardFilter filter) {
        return move(Selector.yours("materials", filter.asCreature(), 1), Move.BACK);
    }

    /** "coloca hasta 1 [filtro] de los materiales de esta carta en tu línea frontal". */
    static EffectStep materialUpToToFront(CardFilter filter) {
        return move(Selector.yours("materials", filter, 1).upTo(), Move.FRONT);
    }

    /** "añádele todos los materiales de esta carta": to the card just moved. */
    static EffectStep materialsToLast() {
        return move(Selector.all("materials", CardFilter.any()), "materials:last");
    }

    /** "sube los materiales de esta carta a tu mano"; {@code upTo}: those the player picks. */
    static EffectStep materialsToHand(boolean upTo) {
        return move(upTo ? new Selector("materials", Selector.Owner.YOU, CardFilter.any(), new Amount.Fixed(0), new Amount.Fixed(Integer.MAX_VALUE), Selector.Self.ANY, Selector.Mode.CHOOSE)
                : Selector.all("materials", CardFilter.any()), Move.HAND);
    }

    static EffectStep addResourceFrom(String zones, CardFilter filter) {
        return addResourceFromZone(zones, filter, false);
    }

    static EffectStep addResourceFromZone(String zones, CardFilter filter, boolean upTo) {
        Selector cards = Selector.yours(zones, filter, 1).with(Selector.Self.EXCLUDE);
        return new EffectStep.Do(new Action.Move(upTo ? cards.upTo() : cards, "resources:self", false, null, !upTo));
    }

    static EffectStep addAllyAsResource(CardFilter filter) {
        return move(Selector.yours("allies", filter, 1).with(Selector.Self.EXCLUDE), "resources:self");
    }

    // ------------------------------------------------------------------ Lives

    static EffectStep deckTopToLife() {
        return new EffectStep.Do(new Action.Move(Selector.yours("deck", CardFilter.any(), 1).as(Selector.Mode.TOP), Move.LIFE_TOP, false, null, false, false, true, null));
    }

    static EffectStep materialToLife() {
        return move(Selector.yours("materials", CardFilter.any(), 1).upTo(), Move.LIFE_TOP);
    }

    static EffectStep zoneToLife(String zones, CardFilter filter, boolean faceUp) {
        return new EffectStep.Do(new Action.Move(Selector.yours(zones, filter, 1).upTo().with(Selector.Self.EXCLUDE), Move.LIFE_TOP, false, null, false, false, !faceUp, null));
    }

    // ------------------------------------------------------------------ Looked cards: one choice, the rest ordered in it

    private static EffectStep fromLooked(CardFilter filter, int min, int max, String to, String rest, boolean faceDown) {
        Selector looked = new Selector("looked", Selector.Owner.YOU, filter, new Amount.Fixed(min), new Amount.Fixed(max), Selector.Self.ANY, Selector.Mode.CHOOSE);
        return new EffectStep.Do(new Action.Move(looked, to, false, null, false, false, faceDown, rest));
    }

    /** "de ellas toma hasta N [filtro]. Envía el resto al fondo de tu mazo". */
    static EffectStep takeFromLookedRestToBottom(int max, CardFilter filter) {
        return fromLooked(filter, 0, max, Move.HAND, Move.DECK_BOTTOM, false);
    }

    /** "de ellas toma hasta N [filtro]" leaving the rest with the effect. */
    static EffectStep takeFromLooked(int max, CardFilter filter) {
        return fromLooked(filter, 0, max, Move.HAND, null, false);
    }

    static EffectStep tributeFromLooked(int max, CardFilter filter) {
        return fromLooked(filter, 0, max, Move.TRIBUTES, null, false);
    }

    static EffectStep tributeFromLookedRestToBottom(int count) {
        return fromLooked(CardFilter.any(), count, count, Move.TRIBUTES, Move.DECK_BOTTOM, false);
    }

    static EffectStep discardFromLookedRestToBottom(int max) {
        return fromLooked(CardFilter.any(), 0, max, Move.DISCARD, Move.DECK_BOTTOM, false);
    }

    /** "de ellas descarta al menos N bocabajo. Coloca el resto al tope de tu mazo en cualquier orden". */
    static EffectStep discardLookedRestTop(int atLeast) {
        return fromLooked(CardFilter.any(), atLeast, Integer.MAX_VALUE, Move.DISCARD, Move.DECK_TOP, true);
    }

    // ------------------------------------------------------------------ Simple actions

    private static EffectStep act(Action action) {
        return new EffectStep.Do(action);
    }

    static EffectStep draw(int count) {
        return act(new Action.Draw(new Amount.Fixed(count), false));
    }

    static EffectStep drawLastCount() {
        return act(new Action.Draw(Amount.LastCount.INSTANCE, false));
    }

    static EffectStep eachPlayerDraws(int count) {
        return act(new Action.Draw(new Amount.Fixed(count), true));
    }

    static EffectStep gainGems(int count) {
        return act(new Action.Gems(new Amount.Fixed(count)));
    }

    static EffectStep damageByEffect(int amount) {
        return act(new Action.Damage(new Amount.Fixed(amount)));
    }

    static EffectStep damageBySelfStrength() {
        return act(new Action.Damage(Amount.SourceStrength.INSTANCE));
    }

    static EffectStep lookTop(int count) {
        return act(new Action.Look(new Amount.Fixed(count)));
    }

    static EffectStep lookTopBy(Amount count) {
        return act(new Action.Look(count));
    }

    // ------------------------------------------------------------------ Exhausting and reactivating

    private static EffectStep setTapped(Selector cards, boolean tapped, boolean required) {
        return act(new Action.SetTapped(cards, tapped, required));
    }

    private static final Selector TARGETS = Selector.saved(Memory.TARGETS);
    private static final Selector SUMMONED = Selector.saved(Memory.SUMMONED);
    private static final Selector SUBJECT_OR_SELF = Selector.all("subjectOrSelf", CardFilter.any());

    static EffectStep tapSelf() {
        return setTapped(Selector.self("field"), true, false);
    }

    static EffectStep tapChosen() {
        return setTapped(TARGETS, true, false);
    }

    static EffectStep tapChosenOnField() {
        return setTapped(TARGETS, true, false);
    }

    static EffectStep tapSummoned() {
        return setTapped(SUMMONED, true, false);
    }

    /** "agota dicho enemigo": the card of the triggering event. */
    static EffectStep tapTriggerCard() {
        return setTapped(Selector.all("eventOnField", CardFilter.any()), true, false);
    }

    static EffectStep tapOwnCard(CardFilter filter) {
        return setTapped(Selector.yours("field", filter, 1).with(Selector.Self.EXCLUDE), true, true);
    }

    static EffectStep untapSelf() {
        return setTapped(Selector.self("field"), false, false);
    }

    static EffectStep untapSubject() {
        return setTapped(SUBJECT_OR_SELF, false, false);
    }

    static EffectStep untapAlly() {
        return untapAlly(CardFilter.any());
    }

    static EffectStep untapAlly(CardFilter filter) {
        return setTapped(Selector.yours("allies", filter, 1), false, true);
    }

    static EffectStep untapAllies(int max) {
        return untapAllies(max, CardFilter.any());
    }

    static EffectStep untapAllies(int max, CardFilter filter) {
        return setTapped(Selector.yours("allies", filter, max).upTo(), false, false);
    }

    static EffectStep untapOwnCard(CardFilter filter) {
        return setTapped(Selector.yours("field", filter, 1), false, false);
    }

    static EffectStep untapStructure(CardFilter filter) {
        return setTapped(Selector.yours("field", filter.asStructure(), 1), false, true);
    }

    static EffectStep untapStructures(int max, CardFilter filter) {
        return setTapped(Selector.yours("field", filter.asStructure(), max).upTo(), false, false);
    }

    // ------------------------------------------------------------------ Strength, abilities and restrictions for a while

    private static EffectStep strength(Selector cards, Amount amount, Action.Until until) {
        return act(new Action.Modify(cards, amount, false, null, until));
    }

    private static EffectStep keyword(Selector cards, String keyword, Action.Until until) {
        return act(new Action.Modify(cards, null, false, keyword, until));
    }

    private static Action.Until until(String until) {
        return switch (until) {
            case "nextTurn" -> Action.Until.NEXT_TURN;
            case "attack" -> Action.Until.ATTACK;
            default -> Action.Until.TURN;
        };
    }

    static EffectStep gainStrength(int amount) {
        return strength(SUBJECT_OR_SELF, new Amount.Fixed(amount), Action.Until.TURN);
    }

    static EffectStep gainStrengthUntil(int amount, String until) {
        return strength(SUBJECT_OR_SELF, new Amount.Fixed(amount), until(until));
    }

    static EffectStep gainStrengthBy(Amount amount) {
        return strength(SUBJECT_OR_SELF, amount, Action.Until.TURN);
    }

    static EffectStep gainStrengthPerCount(int each) {
        return strength(SUBJECT_OR_SELF, new Amount.Times(Amount.LastCount.INSTANCE, each), Action.Until.TURN);
    }

    static EffectStep gainUntilEndOfAttack(String keyword) {
        return keyword(SUBJECT_OR_SELF, keyword, Action.Until.ATTACK);
    }

    static EffectStep gainUntilEndOfTurn(String keyword) {
        return keyword(SUBJECT_OR_SELF, keyword, Action.Until.TURN);
    }

    static EffectStep gainUntilNextTurn(String keyword) {
        return keyword(SUBJECT_OR_SELF, keyword, Action.Until.NEXT_TURN);
    }

    static EffectStep gainUntilNextAttack(int strength, String keyword) {
        return act(new Action.Modify(SUBJECT_OR_SELF, strength == 0 ? null : new Amount.Fixed(strength), false, keyword, Action.Until.NEXT_ATTACK));
    }

    static EffectStep reduceChosen(int amount, boolean untilNextTurn) {
        return strength(TARGETS, new Amount.Fixed(-amount), untilNextTurn ? Action.Until.NEXT_TURN : Action.Until.TURN);
    }

    static EffectStep reduceChosenBy(Amount amount) {
        return strength(TARGETS, new Amount.Times(amount, -1), Action.Until.TURN);
    }

    static EffectStep reduceChosenPerCount(int each) {
        return strength(TARGETS, new Amount.Times(Amount.LastCount.INSTANCE, -each), Action.Until.TURN);
    }

    static EffectStep reduceChosenToZero() {
        return act(new Action.Modify(TARGETS, null, true, null, Action.Until.TURN));
    }

    static EffectStep reduceChosenUntilAttack(int amount) {
        return strength(TARGETS, new Amount.Fixed(-amount), Action.Until.ATTACK);
    }

    static EffectStep chosenCannotBeExhausted(String until) {
        return keyword(TARGETS, EffectEngine.CANNOT_TAP, until(until));
    }

    static EffectStep chosenCannotBlock() {
        return keyword(TARGETS, EffectEngine.NO_BLOCK, Action.Until.TURN);
    }

    static EffectStep summonedCannotAttack() {
        return keyword(SUMMONED, EffectEngine.NO_ATTACK, Action.Until.TURN);
    }

    // ------------------------------------------------------------------ Destroying and sacrificing

    private static EffectStep destroy(Selector cards, boolean required) {
        return act(new Action.Destroy(cards, false, required));
    }

    private static EffectStep sacrifice(Selector cards, boolean required) {
        return act(new Action.Destroy(cards, true, required));
    }

    static EffectStep destroyChosen() {
        return destroy(TARGETS, false);
    }

    static EffectStep destroySelf() {
        return destroy(Selector.self("field"), false);
    }

    static EffectStep destroyAlly(CardFilter filter) {
        return destroy(Selector.yours("allies", filter, 1), true);
    }

    /** "destruye 1 enemigo": without "elige" it is not a target (designer ruling 2026-11-01). */
    static EffectStep destroyEnemy(int count, CardFilter filter) {
        return destroy(Selector.enemies(filter, count), true);
    }

    static EffectStep destroyEnemyNoTarget() {
        return destroy(Selector.enemies(CardFilter.creatures(), 1), false);
    }

    static EffectStep sacrificeSelf() {
        return sacrifice(Selector.self("field"), false);
    }

    static EffectStep sacrificeChosen() {
        return sacrifice(TARGETS, false);
    }

    static EffectStep sacrificeAllyWhere(CardFilter filter) {
        return sacrifice(Selector.yours("allies", filter, 1), true);
    }

    static EffectStep sacrificeAlly() {
        return sacrificeAlly(false, false);
    }

    static EffectStep sacrificeAlly(boolean toUntapAnother) {
        return sacrificeAlly(toUntapAnother, false);
    }

    /** {@code toUntapAnother}: "sacrifica 1 aliado para reactivar otro": it needs another that can be reactivated, the old step until relative filters. */
    static EffectStep sacrificeAlly(boolean toUntapAnother, boolean other) {
        if (toUntapAnother) return new EffectStep.SacrificeAlly(true, other);
        Selector ally = Selector.yours("allies", CardFilter.any(), 1);
        return sacrifice(other ? ally.with(Selector.Self.EXCLUDE) : ally, true);
    }

    // ------------------------------------------------------------------ Choices that depend on the game or on other cards

    private static EffectStep choose(Selector cards) {
        return act(new Action.Select(cards, true, null, null));
    }

    private static Selector exactly(String zones, Selector.Owner owner, CardFilter filter, Amount count) {
        return new Selector(zones, owner, filter, count, count, Selector.Self.ANY, Selector.Mode.CHOOSE);
    }

    /** "por cada material tributado elige 1 enemigo distinto". */
    static EffectStep chooseEnemiesPerCount(CardFilter filter) {
        return choose(exactly("frontLine", Selector.Owner.OPPONENT, filter, Amount.LastCount.INSTANCE));
    }

    static EffectStep chooseEnemiesPerCountEvery(CardFilter filter, int per) {
        return choose(exactly("frontLine", Selector.Owner.OPPONENT, filter, new Amount.Scaled(Amount.LastCount.INSTANCE, per, 0)));
    }

    /** "elige 1 enemigo de nivel igual o menor [que el aliado elegido]". */
    static EffectStep chooseEnemyUpToSubjectLevel(boolean lower) {
        return choose(Selector.enemies(CardFilter.creatures(), 1).with(Selector.Relative.level("subject", lower ? Selector.Compare.LESS : Selector.Compare.AT_MOST, 0)));
    }

    static EffectStep chooseEnemyUpToSummonUsed() {
        return choose(Selector.enemies(CardFilter.creatures(), 1).with(Selector.Relative.read(null, Amount.UsedToSummon.INSTANCE)));
    }

    static EffectStep chooseEnemyBy(int count, EffectStep.FilterBy filter) {
        return chooseEnemyBy(count, filter, false);
    }

    static EffectStep chooseEnemyBy(int count, EffectStep.FilterBy filter, boolean upTo) {
        Selector enemies = Selector.enemies(filter.base(), count).with(Selector.Relative.read(filter.level(), filter.maxLevel()));
        return choose(upTo ? enemies.upTo() : enemies);
    }

    /** "elige 1 criatura [filtro] en el campo": yours or an enemy's, by the normal rules (designer ruling 2026-10-29). */
    static EffectStep chooseCreatureOnField(CardFilter filter, boolean upTo) {
        Selector creatures = exactly("field", Selector.Owner.BOTH, filter.asCreature(), new Amount.Fixed(1)).with(Selector.Self.EXCLUDE);
        return act(new Action.Select(upTo ? creatures.upTo() : creatures, true, null, "Elegir"));
    }

    static EffectStep chooseFrontLineCreatures(int count, CardFilter filter) {
        return choose(exactly("frontLine", Selector.Owner.BOTH, filter.asCreature(), new Amount.Fixed(count)));
    }

    /** "elige todos los enemigos [filtro] en la línea frontal". */
    static EffectStep targetAllEnemies(CardFilter filter) {
        return choose(Selector.enemies(filter, 1).as(Selector.Mode.ALL).atLeast(0).upToAll());
    }

    static EffectStep targetAllEnemies() {
        return targetAllEnemies(CardFilter.creatures());
    }

    // ------------------------------------------------------------------ More phrases

    static EffectStep tributeFromHandAsSubjectLevel() {
        return move(exactly("hand", Selector.Owner.YOU, CardFilter.any(), Amount.SubjectLevel.INSTANCE).with(Selector.Self.EXCLUDE), Move.TRIBUTES);
    }

    /** "envía cartas de tu mano al fondo de tu mazo hasta tener N cartas en tu mano". */
    static EffectStep handDownTo(int keep) {
        return move(exactly("hand", Selector.Owner.YOU, CardFilter.any(), new Amount.HandOver(keep)), Move.DECK_BOTTOM);
    }

    static EffectStep tributeChosen() {
        return move(Selector.saved(Memory.TARGETS), Move.TRIBUTES);
    }

    static EffectStep tributeTopLife() {
        return move(Selector.yours("lifeStack", CardFilter.any(), 1).as(Selector.Mode.TOP), Move.TRIBUTES);
    }

    /** "Si es [filtro]: muévela a tu línea frontal": the creature this effect summoned. */
    static EffectStep moveSummonedToFrontIf(CardFilter filter) {
        return move(new Selector("saved:" + Memory.SUMMONED, Selector.Owner.YOU, filter, new Amount.Fixed(0), new Amount.Fixed(1), Selector.Self.ANY, Selector.Mode.ALL), Move.FRONT);
    }

    /** "envía N copias de esta carta en tu retaguardia a tu [zona]" (this card included). */
    static EffectStep sendCopiesFromField(int count, String zone) {
        return move(Selector.yours("backLine", CardFilter.any(), count).with(Selector.Relative.copiesOf("self")), zone);
    }

    /** "agota hasta tantas copias de esta carta como controles". */
    static EffectStep tapCopiesUpTo() {
        return setTapped(new Selector("field", Selector.Owner.YOU, CardFilter.any(), new Amount.Fixed(1), new Amount.Fixed(Integer.MAX_VALUE), Selector.Self.ANY, Selector.Mode.CHOOSE)
                .with(Selector.Relative.copiesOf("self")), true, false);
    }

    static EffectStep tapSubject() {
        return setTapped(Selector.all("subjectOrSummoned", CardFilter.any()), true, false);
    }

    static EffectStep untapAllySameLevelAsLast() {
        return setTapped(Selector.yours("allies", CardFilter.any(), 1).with(Selector.Relative.level("last", Selector.Compare.EQUAL, 0)), false, false);
    }

    static EffectStep sacrificeAllyWeakerThanSelf() {
        return sacrifice(Selector.yours("allies", CardFilter.any(), 1).with(Selector.Self.EXCLUDE).with(Selector.Relative.weakerThan("self")), true);
    }

    static EffectStep sacrificeAllInFrontLine(CardFilter filter) {
        return sacrifice(new Selector("frontLine", Selector.Owner.BOTH, filter.asCreature(), new Amount.Fixed(0), new Amount.Fixed(Integer.MAX_VALUE), Selector.Self.ANY, Selector.Mode.ALL), false);
    }

    /** "sacrifica ambas criaturas": the enemy it clashes with and this card. */
    static EffectStep sacrificeClashing() {
        return sacrifice(new Selector("clash,thisCard", Selector.Owner.YOU, CardFilter.any(), new Amount.Fixed(0), new Amount.Fixed(2), Selector.Self.ANY, Selector.Mode.ALL), false);
    }

    /** "reduce su fuerza en X" on the creature this card clashes with. */
    static EffectStep reduceClashOpponent(Amount amount) {
        return strength(Selector.all("clash", CardFilter.any()), new Amount.Times(amount, -1), Action.Until.TURN);
    }

    /** "reducirla a 0": the enemy whose strength was reduced in the triggering event. */
    static EffectStep reduceTriggerToZero() {
        return act(new Action.Modify(Selector.all("eventOnField", CardFilter.any()), null, true, null, Action.Until.TURN));
    }

    static EffectStep reduceTriggerFurther(int amount) {
        return strength(Selector.all("eventOnField", CardFilter.any()), new Amount.Fixed(-amount), Action.Until.AS_EVENT);
    }

    // ------------------------------------------------------------------ Summoning and playing by effect

    /** "sin pagar su coste". */
    static final int FREE = Action.Summon.FREE;

    private static final String ANYWHERE_BUT_FIELD = "hand,deck,tributeZone,discardPile,lifeStack";

    static EffectStep summonFromZone(String zones, CardFilter filter, int reduce) {
        return summonFromZone(zones, filter, reduce, false);
    }

    static EffectStep summonFromZone(String zones, CardFilter filter, int reduce, boolean toFront) {
        return act(new Action.Summon(Selector.yours(zones, filter, 1), new Amount.Fixed(reduce), toFront, true));
    }

    static EffectStep summonFromZoneBy(String zones, EffectStep.FilterBy filter, int reduce) {
        return act(new Action.Summon(Selector.yours(zones, filter.base(), 1).with(Selector.Relative.read(filter.level(), filter.maxLevel())), new Amount.Fixed(reduce), false, true));
    }

    static EffectStep summonSelf(String zone, Amount reduce, boolean toFront) {
        return act(new Action.Summon(Selector.self(zone), reduce, toFront, true));
    }

    static EffectStep summonSelfFree() {
        return act(new Action.Summon(Selector.self(ANYWHERE_BUT_FIELD), new Amount.Fixed(FREE), false, true));
    }

    static EffectStep summonSelfFromZone(String zone, int reduce) {
        return act(new Action.Summon(Selector.self(zone), new Amount.Fixed(reduce), false, true));
    }

    /** "reduciendo su coste en 1 por cada N cartas enviadas". */
    static EffectStep summonSelfPerCount(String zone, int per) {
        return act(new Action.Summon(Selector.self(zone), new Amount.Scaled(Amount.LastCount.INSTANCE, per, 0), false, false));
    }

    static EffectStep summonUpTo(String zones, CardFilter filter, int max, int reduce) {
        return act(new Action.Summon(Selector.yours(zones, filter, max).upTo(), new Amount.Fixed(reduce), false, false));
    }

    /** "invoca 1 de los recursos de esta carta": none is fine. */
    static EffectStep summonResource(int reduce) {
        return act(new Action.Summon(Selector.yours("resources", CardFilter.creatures(), 1).upTo(), new Amount.Fixed(reduce), false, true));
    }

    /** "de nivel igual a la cantidad de [copias agotadas]", free. */
    static EffectStep summonLevelLastCount(String zones, CardFilter filter) {
        return act(new Action.Summon(Selector.yours(zones, filter, 1).with(Selector.Relative.read(Amount.LastCount.INSTANCE, null)), new Amount.Fixed(FREE), false, true));
    }

    /** "de nivel menor [que el aliado destruido]" ("lower"), or "de nivel igual o menor, pero distinto nombre". */
    static EffectStep summonRelativeToLast(String zones, CardFilter filter, int reduce, boolean toFront, String relation) {
        Selector.Relative relative = "lower".equals(relation) ? Selector.Relative.level("last", Selector.Compare.LESS, 0) : Selector.Relative.upToLevelOtherName("last");
        return act(new Action.Summon(Selector.yours(zones, filter, 1).with(relative), new Amount.Fixed(reduce), toFront, true));
    }

    static EffectStep playFromZone(String zones, CardFilter filter, int reduce) {
        return act(new Action.Play(Selector.yours(zones, filter, 1), new Amount.Fixed(reduce), true));
    }

    /** "reduciendo su coste en N por cada carta tributada". */
    static EffectStep playFromZoneByCount(String zones, CardFilter filter, int each) {
        return act(new Action.Play(Selector.yours(zones, filter, 1), new Amount.Times(Amount.LastCount.INSTANCE, each), true));
    }

    static EffectStep playSelfFree() {
        return act(new Action.Play(Selector.self("hand"), new Amount.Fixed(FREE), true));
    }

    static EffectStep playSelfFromZone(String zone, boolean reduceBySourceLevel) {
        return act(new Action.Play(Selector.self(zone), reduceBySourceLevel ? Amount.TriggerSourceLevel.INSTANCE : new Amount.Fixed(0), true));
    }

    static EffectStep playSelfReduced(String zone, int reduce) {
        return act(new Action.Play(Selector.self(zone), new Amount.Fixed(reduce), true));
    }

    // ------------------------------------------------------------------ Special summons: the creature, its summoner, its materials, and the summon

    private static EffectStep special(SpecialRule rule) {
        return act(new Action.Sequence(List.of(
                new Action.Select(Selector.of(rule, Selector.Stage.CARD), false, EngineContext.SPECIAL_CARD, "Invocar"),
                new Action.Select(Selector.of(rule, Selector.Stage.SUMMONER), false, EngineContext.SPECIAL_SUMMONER, "Invocador"),
                new Action.Select(Selector.of(rule, Selector.Stage.MATERIAL), false, EngineContext.SPECIAL_MATERIALS, "Material"),
                new Action.SummonSpecial(rule))));
    }

    /** "[1 aliado] puede invocar por [método] 1 criatura [filtro] de tu [zona] reduciendo el coste especial en N". */
    static EffectStep specialFromZone(String kind, String zone, int reduce, String summoner, CardFilter filter) {
        return special(new SpecialRule(kind, zone, reduce, summoner, filter));
    }

    /** "otro aliado" (not this card) or "1 aliado" (any). */
    static EffectStep specialFromZone(String kind, String zone, int reduce, boolean otherAlly) {
        return specialFromZone(kind, zone, reduce, otherAlly ? "other" : "any", CardFilter.any());
    }

    /** "un aliado invoca esta carta de forma especial [reduciendo su coste especial en N]". */
    static EffectStep specialSelf(String zone, int reduce) {
        return special(new SpecialRule(SpecialRule.OF_THIS_CARD, zone, reduce, "any", CardFilter.any()));
    }

    /** "reduciendo el coste especial en X donde X es el nivel del aliado elegido". */
    static EffectStep specialBySubjectLevel(String kind, String zone, CardFilter filter) {
        return special(new SpecialRule(kind, zone, 0, "subject", filter, Amount.SubjectLevel.INSTANCE, null));
    }

    /** "reduciendo el coste especial en 1 por cada … tributado". */
    static EffectStep specialByCount(String kind, String zone, String summoner, CardFilter filter) {
        return special(new SpecialRule(kind, zone, 0, summoner, filter, Amount.LastCount.INSTANCE, null));
    }

    /** "del mismo nivel, pero distinto nombre [que la carta devuelta]". */
    static EffectStep specialRelativeToLast(String zone, CardFilter filter, int reduce) {
        return special(new SpecialRule("any", zone, reduce, "any", filter, null, Selector.Relative.sameLevelOtherName("last")));
    }

    private static EffectStep ritual(SpecialRule rule) {
        return act(new Action.Sequence(List.of(
                new Action.Select(Selector.of(rule, Selector.Stage.CARD), false, EngineContext.SPECIAL_CARD, "Invocar"),
                new Action.Select(Selector.of(rule, Selector.Stage.SUMMONER), false, EngineContext.SPECIAL_SUMMONER, "Invocador"),
                new Action.Select(Selector.of(rule, Selector.Stage.MATERIAL), false, EngineContext.SPECIAL_MATERIALS, "Sacrificar"),
                new Action.SummonSpecial(rule))));
    }

    /** "[esta carta / un aliado] invoca por Ritual 1 criatura de tus [zona] reduciendo su coste especial en N". */
    static EffectStep ritualFromZone(String zone, boolean selfSummoner, int reduce) {
        return ritualFromZone(zone, selfSummoner, reduce, CardFilter.any());
    }

    static EffectStep ritualFromZone(String zone, boolean selfSummoner, int reduce, CardFilter filter) {
        return ritual(new SpecialRule(SpecialRule.RITUAL, zone, reduce, selfSummoner ? "self" : "any", filter));
    }

    static EffectStep ritualFromZoneBy(String zone, boolean selfSummoner, Amount reduce, CardFilter filter) {
        return ritual(new SpecialRule(SpecialRule.RITUAL, zone, 0, selfSummoner ? "self" : "any", filter, reduce, null));
    }

    // ------------------------------------------------------------------ Choosing an ally the next actions act on

    private static EffectStep pick(String zones, CardFilter filter, boolean target) {
        return act(new Action.Select(Selector.yours(zones, filter, 1), target, Memory.SUBJECT, "Elegido", true));
    }

    static EffectStep pickAlly() {
        return pickAlly(false, false);
    }

    static EffectStep pickAlly(boolean attacker) {
        return pickAlly(attacker, false);
    }

    /** {@code attacker}: it is going to declare an attack; {@code target}: the text says "elige". */
    static EffectStep pickAlly(boolean attacker, boolean target) {
        return pick(attacker ? "pickableAttackers" : "pickableAllies", CardFilter.any(), target);
    }

    static EffectStep pickAllyWhere(CardFilter filter) {
        return pickAllyWhere(filter, false);
    }

    static EffectStep pickAllyWhere(CardFilter filter, boolean target) {
        return pick("pickableAllies", filter, target);
    }

    static EffectStep pickAttacker(CardFilter filter) {
        return pickAttacker(filter, false);
    }

    static EffectStep pickAttacker(CardFilter filter, boolean target) {
        return pick("attackers", filter, target);
    }
}
