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
    record ChooseEnemy(int count, CardFilter filter, boolean upTo) implements EffectStep {
        public ChooseEnemy(int count, CardFilter filter) { this(count, filter, false); }
    }

    /** "destrúyelo": destroys the targets chosen on activation that are still valid (rule 11.10.2). */
    record DestroyChosen() implements EffectStep {}

    /**
     * "invoca 1 criatura [filtro] de tus [zona] reduciendo su coste en N": summon by effect (rule 9.1.0.2),
     * paying the normal cost minus {@code reduce}; it enters the Retaguardia with the regulatory draw.
     */
    record SummonFromZone(String zone, CardFilter filter, int reduce, boolean toFront) implements EffectStep {
        public SummonFromZone(String zone, CardFilter filter, int reduce) { this(zone, filter, reduce, false); }
    }

    /** "invoca esta carta sin pagar su coste" from the hand: a summon by effect. */
    record SummonSelfFree() implements EffectStep {}

    /**
     * "[otro aliado / 1 aliado] puede invocar por [Ascenso / Evolución] 1 criatura de tu [zona] reduciendo el coste
     * especial en N": the controller may pick the creature (or none), then the summoner.
     */
    record SpecialFromZone(String kind, String zone, int reduce, String summoner, CardFilter filter) implements EffectStep {
        /** "otro aliado" (not this card) or "1 aliado" (any). */
        public SpecialFromZone(String kind, String zone, int reduce, boolean otherAlly) {
            this(kind, zone, reduce, otherAlly ? "other" : "any", CardFilter.any());
        }
    }

    /** "tributa [hasta] N materiales de esta carta" (a cost); lastCount is how many. */
    record TributeMaterials(int count, boolean upTo) implements EffectStep {}

    /** "toma 1 carta [filtro] de tus [zona] por cada material tributado" (lastCount). */
    record TakePerCount(String zone, CardFilter filter) implements EffectStep {}

    /** "tributa hasta N cartas de tu mano"; lastCount is how many. */
    record TributeFromHandUpTo(int count) implements EffectStep {}

    /** "roba la misma cantidad" (lastCount). */
    record DrawLastCount() implements EffectStep {}

    /** "Si tributaste 1 carta [filtro]:": the next {@code covers} steps only then. */
    record IfTributed(CardFilter filter, int covers) implements EffectStep {}

    /** "reduce su fuerza en N" on the chosen targets; {@code untilNextTurn}: until the end of the next turn. */
    record ReduceChosen(int amount, boolean untilNextTurn) implements EffectStep {}

    /** "elige todos los enemigos en la línea frontal" (no choice: they are the targets). */
    record TargetAllEnemies() implements EffectStep {}

    /** "reducirla a 0": the enemy whose strength was reduced in the triggering event. */
    record ReduceTriggerToZero() implements EffectStep {}

    /** "coloca hasta 1 material de esta carta al tope de tus vidas bocarriba". */
    record MaterialToLife() implements EffectStep {}

    /** "coloca la carta del tope de tu mazo al tope de tus vidas bocabajo". */
    record DeckTopToLife() implements EffectStep {}

    /** "infringe N de daño directo" by effect (to the top life). */
    record DamageByEffect(int amount) implements EffectStep {}

    /** "puedes invocar esta carta [desde zona] reduciendo su coste en N". */
    record SummonSelfFromZone(String zone, int reduce) implements EffectStep {}

    /** "añadele 1 criatura [filtro] de menor nivel de tus tributos como material" (to the ally of the triggering event). */
    record AttachLowerFromTributes(CardFilter filter) implements EffectStep {}

    /** "coloca esta carta agotada en tu retaguardia": the utility stays on the field. */
    record KeepOnField() implements EffectStep {}

    /** "tributa N cartas del tope de tu mazo". */
    record TributeTop(int count) implements EffectStep {}

    /** "coloca [hasta] N cartas de tu [zona] al tope de tu mazo". */
    record ToDeckTop(String zone, int count, boolean upTo) implements EffectStep {}

    /** "puedes tributar "X" de los recursos de esta carta" (a cost). */
    record TributeResource(String name) implements EffectStep {}

    /** "destruye 1 carta bocarriba en las vidas oponentes". */
    record DestroyFaceUpLife() implements EffectStep {}

    /** "gana [habilidad] hasta el final del ataque". */
    record GainUntilEndOfAttack(String keyword) implements EffectStep {}

    /** "puedes jugar esta carta [desde zona] reduciendo su coste en el nivel de dicho aliado". */
    record PlaySelfFromZone(String zone, boolean reduceBySourceLevel) implements EffectStep {}

    /** "toma N carta [filtro] de tus [zona]": to the hand (up to N when {@code upTo}). */
    record TakeFromZone(String zone, CardFilter filter, int count, boolean upTo) implements EffectStep {}

    /** "Si lo haces:": the next {@code covers} steps only if the last optional step was done. */
    record IfDone(int covers) implements EffectStep {}

    /** "gana fuerza N hasta el final del turno": this card or the chosen ally. */
    record GainStrength(int amount) implements EffectStep {}

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
    record DeclareAttack(boolean opponentOnly) implements EffectStep {
        public DeclareAttack() { this(false); }
    }

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
    record UntapAlly(CardFilter filter) implements EffectStep {
        public UntapAlly() { this(CardFilter.any()); }
    }

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
