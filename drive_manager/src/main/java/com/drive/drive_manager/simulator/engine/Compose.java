package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.engine.effects.model.Action;
import com.drive.drive_manager.simulator.engine.effects.model.Action.Move;
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
}
