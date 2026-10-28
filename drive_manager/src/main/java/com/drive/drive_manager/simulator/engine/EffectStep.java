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
     * "elige N enemigo [filtro];" as a cost: the targets are chosen when the effect is activated (rule 11.10.1);
     * the effect can only be activated if there are enough of them. Only the opponent's Línea frontal.
     */
    record ChooseEnemy(int count, CardFilter filter) implements EffectStep {}

    /** "destrúyelo": destroys the targets chosen on activation that are still valid (rule 11.10.2). */
    record DestroyChosen() implements EffectStep {}

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

    /** "gana [habilidad] hasta el final del turno": this card, or the ally chosen by {@link PickAlly}. */
    record GainUntilEndOfTurn(String keyword) implements EffectStep {}

    /**
     * "declara ataque": this card, or the ally chosen by {@link PickAlly}, attacks without being exhausted
     * (rule 11.9.3.1). Its controller picks an exhausted enemy creature, or none to attack the opponent.
     */
    record DeclareAttack() implements EffectStep {}

    /**
     * "elige 1 aliado": the ally the next steps act on. With {@code attacker} it is going to declare an attack, so
     * only allies of the Línea frontal can be chosen.
     */
    record PickAlly(boolean attacker) implements EffectStep {
        public PickAlly() { this(false); }
    }

    /** "gana N gemas": moves the Contador towards the controller. */
    record GainGems(int count) implements EffectStep {}

    /** "agota esta carta" (a cost: it must be active on the field). */
    record TapSelf() implements EffectStep {}

    /** "sacrifica esta carta" (a cost: it must be on the field). */
    record SacrificeSelf() implements EffectStep {}

    /**
     * "sacrifica 1 aliado": the controller picks one of their creatures on the field. With {@code toUntapAnother}
     * ("sacrifica 1 aliado para reactivar otro") both must be possible: the one sacrificed cannot be the only
     * ally left to reactivate.
     */
    record SacrificeAlly(boolean toUntapAnother) implements EffectStep {
        public SacrificeAlly() { this(false); }
    }

    /** "reactiva 1 aliado" / "reactivar otro": the controller picks an exhausted ally that can be reactivated. */
    record UntapAlly() implements EffectStep {}

    /** Celeridad: "puede subir a tu línea frontal". */
    record MoveSelfToFront() implements EffectStep {}

    /** "puedes jugar esta carta sin pagar su coste" (from the hand). */
    record PlaySelfFree() implements EffectStep {}

    /**
     * "[esta carta / un aliado] invoca por Ritual 1 criatura de tus [zona] reduciendo su coste especial en N"
     * (rule 9.3): the controller picks the creature, then the summoner and the other creatures that meet its
     * Requerimiento; they are sacrificed and it is summoned in the summoner's place.
     */
    record RitualFromZone(String zone, boolean selfSummoner, int reduce) implements EffectStep {}

    /**
     * "puedes A; B": asks the controller. If they decline, or A cannot be done, the next {@code covers}
     * steps (A and B) are skipped.
     */
    record MayDo(String question, int covers) implements EffectStep {}
}
