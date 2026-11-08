package com.drive.drive_manager.simulator.engine

import kotlin.jvm.JvmRecord
/**
 * "Cuando … fuera a …": what a card does to an event before it happens. It never triggers nor chains (rule 11.5): it lowers a cost, acts
 * first and lets the event go on, or replaces it ("en su lugar").
 *
 * Its members: [CostModifiers.CostModifier], [CardEffects.Replacement], [CardEffects.AllyRemovalSummon], [CardEffects.AllyGuard],
 * [CardEffects.DamageReplacement] and those below.
 */
sealed interface Reaction {

    /** "cuando fueras a recibir daño: tribútala con 2 [retador]". */
    data object TributesInsteadOfDamage : Reaction

    /** "cuando un aliado sea declarado bloqueador: en su lugar, elige otro". */
    data object SwapsBlocker : Reaction

    /** "cuando fuera a ser ascendida: puede declarar ataque". */
    data object AttacksBeforeAscended : Reaction

    /** "cuando fuera a ser ascendida: gana [habilidad]". */
    @JvmRecord
    data class GainBeforeAscended(val keyword: String?) : Reaction
}
