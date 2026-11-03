package com.drive.drive_manager.simulator.engine;

/**
 * "Cuando … fuera a …": what a card does to an event before it happens. It never triggers nor chains (rule 11.5): it lowers a cost, acts
 * first and lets the event go on, or replaces it ("en su lugar").
 */
public sealed interface Reaction permits CostModifiers.CostModifier, CardEffects.Replacement, CardEffects.AllyRemovalSummon, CardEffects.AllyGuard,
        CardEffects.DamageReplacement, Reaction.TributesInsteadOfDamage, Reaction.SwapsBlocker, Reaction.AttacksBeforeAscended,
        Reaction.GainBeforeAscended {

    /** "cuando fueras a recibir daño: tribútala con 2 [retador]". */
    record TributesInsteadOfDamage() implements Reaction {}

    /** "cuando un aliado sea declarado bloqueador: en su lugar, elige otro". */
    record SwapsBlocker() implements Reaction {}

    /** "cuando fuera a ser ascendida: puede declarar ataque". */
    record AttacksBeforeAscended() implements Reaction {}

    /** "cuando fuera a ser ascendida: gana [habilidad]". */
    record GainBeforeAscended(String keyword) implements Reaction {}
}
