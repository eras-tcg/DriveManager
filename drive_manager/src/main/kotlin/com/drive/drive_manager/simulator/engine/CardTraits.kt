package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.engine.CardEffects.AlternativeLevel
import com.drive.drive_manager.simulator.engine.CardEffects.FieldPiecesDiscount

/**
 * What a card changes in the rules themselves rather than through an effect that activates or a [Reaction]. Plain data, kept with the card
 * ([CardDefinition]); null means the card does not have it.
 *
 * @param alias               "puede ser tratada como X"
 * @param specialFromTributes "En Tributos: cuenta como en la mano para invocaciones especiales"
 * @param alternativeLevel    "puede ser considerada nivel N por tus cartas [tipo]"
 * @param fieldPiecesDiscount "cuesta N menos si utilizas M criaturas de tu campo"
 * @param creatureForEffects  "cuenta como criatura de fuerza N para efectos aliados"
 * @param sacrificeToSummon   "se invoca sacrificando un aliado [filtro]"
 * @param lifePlayCost        "para jugar esta carta desde tus vidas debes pagar N gemas"
 * @param oneEffectPerTurn    "solo 1 de sus efectos por turno"
 */
@JvmRecord
data class CardTraits(
    val alias: String?, val specialFromTributes: Boolean, val alternativeLevel: AlternativeLevel?, val fieldPiecesDiscount: FieldPiecesDiscount?,
    val creatureForEffects: Int?, val sacrificeToSummon: CardFilter?, val lifePlayCost: Int?, val oneEffectPerTurn: Boolean,
) {
    companion object {
        @JvmField
        val NONE = CardTraits(null, false, null, null, null, null, null, false)
    }
}
