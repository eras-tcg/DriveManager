package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.engine.CardEffects.AllyGuard;
import com.drive.drive_manager.simulator.engine.CardEffects.AllyRemovalSummon;
import com.drive.drive_manager.simulator.engine.CardEffects.AlternativeLevel;
import com.drive.drive_manager.simulator.engine.CardEffects.DamageReplacement;
import com.drive.drive_manager.simulator.engine.CardEffects.FieldPiecesDiscount;

/**
 * What a card changes in the rules themselves rather than through an effect that activates. Plain data, kept with the card
 * ({@link CardDefinition}); null means the card does not have it.
 *
 * @param alias                "puede ser tratada como X"
 * @param specialFromTributes  "En Tributos: cuenta como en la mano para invocaciones especiales"
 * @param tributesInsteadOfDamage "cuando fueras a recibir daño: tribútala con 2 [retador]"
 * @param damageReplacement    "se invoca en lugar de recibir N o menos de daño"
 * @param alternativeLevel     "puede ser considerada nivel N por tus cartas [tipo]"
 * @param fieldPiecesDiscount  "cuesta N menos si utilizas M criaturas de tu campo"
 * @param creatureForEffects   "cuenta como criatura de fuerza N para efectos aliados"
 * @param sacrificeToSummon    "se invoca sacrificando un aliado [filtro]"
 * @param attacksBeforeAscended "cuando fuera a ser ascendida: puede declarar ataque"
 * @param gainBeforeAscended   "cuando fuera a ser ascendida: gana [habilidad]"
 * @param lifePlayCost         "para jugar esta carta desde tus vidas debes pagar N gemas"
 * @param swapsBlocker         "cuando un aliado sea declarado bloqueador: en su lugar, elige otro"
 * @param allyRemovalSummon    "cuando algún aliado fuera a ser removido: en su lugar, puede invocar especialmente esta carta"
 * @param allyGuard            "cuando aliados fueran a ser removidos por efecto oponente: en su lugar, …"
 */
public record CardTraits(String alias, boolean specialFromTributes, boolean tributesInsteadOfDamage, DamageReplacement damageReplacement,
                         AlternativeLevel alternativeLevel, FieldPiecesDiscount fieldPiecesDiscount, Integer creatureForEffects, CardFilter sacrificeToSummon,
                         boolean attacksBeforeAscended, String gainBeforeAscended, Integer lifePlayCost, boolean swapsBlocker,
                         AllyRemovalSummon allyRemovalSummon, AllyGuard allyGuard) {

    public static final CardTraits NONE = new CardTraits(null, false, false, null, null, null, null, null, false, null, null, false, null, null);

    static CardTraits alias(String name) {
        return new CardTraits(name, false, false, null, null, null, null, null, false, null, null, false, null, null);
    }

    static CardTraits fromTributes() {
        return new CardTraits(null, true, false, null, null, null, null, null, false, null, null, false, null, null);
    }

    static CardTraits damageTributes() {
        return new CardTraits(null, false, true, null, null, null, null, null, false, null, null, false, null, null);
    }

    static CardTraits of(DamageReplacement damage) {
        return new CardTraits(null, false, false, damage, null, null, null, null, false, null, null, false, null, null);
    }

    static CardTraits of(AlternativeLevel level) {
        return new CardTraits(null, false, false, null, level, null, null, null, false, null, null, false, null, null);
    }

    static CardTraits of(FieldPiecesDiscount discount) {
        return new CardTraits(null, false, false, null, null, discount, null, null, false, null, null, false, null, null);
    }

    static CardTraits creatureForEffects(int strength) {
        return new CardTraits(null, false, false, null, null, null, strength, null, false, null, null, false, null, null);
    }

    static CardTraits sacrificeToSummon(CardFilter filter) {
        return new CardTraits(null, false, false, null, null, null, null, filter, false, null, null, false, null, null);
    }

    static CardTraits attackBeforeAscended() {
        return new CardTraits(null, false, false, null, null, null, null, null, true, null, null, false, null, null);
    }

    static CardTraits gainBeforeAscended(String keyword) {
        return new CardTraits(null, false, false, null, null, null, null, null, false, keyword, null, false, null, null);
    }

    static CardTraits lifePlayCost(int gems) {
        return new CardTraits(null, false, false, null, null, null, null, null, false, null, gems, false, null, null);
    }

    static CardTraits blockerSwap() {
        return new CardTraits(null, false, false, null, null, null, null, null, false, null, null, true, null, null);
    }

    static CardTraits of(AllyRemovalSummon summon) {
        return new CardTraits(null, false, false, null, null, null, null, null, false, null, null, false, summon, null);
    }

    static CardTraits of(AllyGuard guard) {
        return new CardTraits(null, false, false, null, null, null, null, null, false, null, null, false, null, guard);
    }
}
