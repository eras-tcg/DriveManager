package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.engine.effects.model.Action;
import com.drive.drive_manager.simulator.engine.effects.model.Action.Move;
import com.drive.drive_manager.simulator.engine.effects.model.Memory;
import com.drive.drive_manager.simulator.engine.effects.model.Selector;

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
        return move(new Selector(zone, Selector.Owner.YOU, filter, new Amount.LastCount(), new Amount.LastCount(), Selector.Self.ANY, Selector.Mode.CHOOSE), Move.HAND);
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
}
