package com.drive.drive_manager.simulator.engine;

/**
 * One instruction of an effect's resolution, run in order. Steps that need a player's choice pause the
 * engine with a prompt. "Luego:" is plain order (rule 11.1.3.2): the next step runs whether or not this one
 * did anything.
 */
public sealed interface EffectStep {

    /** "mira las primeras N cartas de tu mazo" (fewer if the deck is shorter). */
    record LookTop(int count) implements EffectStep {}

    /**
     * "de ellas toma hasta N cartas [filtro]. Envía el resto al fondo de tu mazo": the controller picks up
     * to {@code max} looked cards that match the filter to add to the hand, and orders the rest, which go to
     * the bottom of the deck in that order.
     */
    record TakeFromLookedRestToBottom(int max, CardFilter filter) implements EffectStep {}

    /** "tributa N cartas de tu mano": the controller picks exactly N (all of them if the hand has fewer). */
    record TributeFromHand(int count) implements EffectStep {}
}
