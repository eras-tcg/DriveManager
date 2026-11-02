package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.engine.CardEffects.AlternativeLevel;
import com.drive.drive_manager.simulator.engine.CardEffects.FieldPiecesDiscount;

/**
 * What a card changes in the rules themselves rather than through an effect that activates or a {@link Reaction}. Plain data, kept with the card
 * ({@link CardDefinition}); null means the card does not have it.
 *
 * @param alias               "puede ser tratada como X"
 * @param specialFromTributes "En Tributos: cuenta como en la mano para invocaciones especiales"
 * @param alternativeLevel    "puede ser considerada nivel N por tus cartas [tipo]"
 * @param fieldPiecesDiscount "cuesta N menos si utilizas M criaturas de tu campo"
 * @param creatureForEffects  "cuenta como criatura de fuerza N para efectos aliados"
 * @param sacrificeToSummon   "se invoca sacrificando un aliado [filtro]"
 * @param lifePlayCost        "para jugar esta carta desde tus vidas debes pagar N gemas"
 */
public record CardTraits(String alias, boolean specialFromTributes, AlternativeLevel alternativeLevel, FieldPiecesDiscount fieldPiecesDiscount,
                         Integer creatureForEffects, CardFilter sacrificeToSummon, Integer lifePlayCost) {

    public static final CardTraits NONE = new CardTraits(null, false, null, null, null, null, null);

    static CardTraits alias(String name) {
        return new CardTraits(name, false, null, null, null, null, null);
    }

    static CardTraits fromTributes() {
        return new CardTraits(null, true, null, null, null, null, null);
    }

    static CardTraits of(AlternativeLevel level) {
        return new CardTraits(null, false, level, null, null, null, null);
    }

    static CardTraits of(FieldPiecesDiscount discount) {
        return new CardTraits(null, false, null, discount, null, null, null);
    }

    static CardTraits creatureForEffects(int strength) {
        return new CardTraits(null, false, null, null, strength, null, null);
    }

    static CardTraits sacrificeToSummon(CardFilter filter) {
        return new CardTraits(null, false, null, null, null, filter, null);
    }

    static CardTraits lifePlayCost(int gems) {
        return new CardTraits(null, false, null, null, null, null, gems);
    }
}
