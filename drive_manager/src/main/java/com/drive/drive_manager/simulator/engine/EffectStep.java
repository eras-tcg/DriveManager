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

    /** "roba N cartas" (fewer if the deck is shorter). */
    record Draw(int count) implements EffectStep {}

    /**
     * "elige N enemigo [filtro]; destrúyelo": the controller picks exactly N enemies of the Línea frontal
     * (rule 4.0.6.2: the Retaguardia cannot be chosen by the opponent's effects). Destruction by effect.
     */
    record DestroyEnemy(int count, CardFilter filter) implements EffectStep {}

    /**
     * "invoca 1 criatura [filtro] de tus [zona] reduciendo su coste en N": summon by effect (rule 9.1.0.2),
     * paying the normal cost minus {@code reduce}; it enters the Retaguardia with the regulatory draw.
     */
    record SummonFromZone(String zone, CardFilter filter, int reduce) implements EffectStep {}

    /** "envía esta carta a tu [zona]" (Slicker pays its effect this way). Only from another zone. */
    record SendSelfTo(String zone) implements EffectStep {}

    /** "envía N cartas [filtro] de tus [zona] al fondo de tu mazo": exactly N, in the order chosen. */
    record SendFromZoneToDeckBottom(String zone, int count, CardFilter filter) implements EffectStep {}

    /** "reactiva esta carta". */
    record UntapSelf() implements EffectStep {}

    /** "esta carta gana [habilidad] hasta el final del turno". */
    record GainUntilEndOfTurn(String keyword) implements EffectStep {}

    /** "esta carta declara ataque". Combat is still played on the board: the engine records the declaration. */
    record DeclareAttack() implements EffectStep {}

    /**
     * "puedes A; B": asks the controller. If they decline, or A cannot be done, the next {@code covers}
     * steps (A and B) are skipped.
     */
    record MayDo(String question, int covers) implements EffectStep {}
}
