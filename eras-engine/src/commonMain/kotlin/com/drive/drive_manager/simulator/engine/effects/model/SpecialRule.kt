package com.drive.drive_manager.simulator.engine.effects.model

import kotlin.jvm.JvmRecord
import com.drive.drive_manager.simulator.engine.Amount
import com.drive.drive_manager.simulator.engine.CardFilter

/**
 * What an effect allows for a special summon: "[1 aliado] puede invocar por [método] 1 criatura [filtro] de tu [zona] reduciendo el
 * coste especial en N". The composition picks the creature, its summoner and its materials under this rule, then summons.
 *
 * @param kind     promotion, evolution, materialization, ritual, any, or [OF_THIS_CARD]
 * @param zone     comma separated zones the creature comes from
 * @param reduce   how much the special cost goes down
 * @param summoner any, other, self, subject or level:N
 * @param filter   which creatures
 * @param reduceBy the reduction when it depends on the game; null: `reduce`
 * @param relative it depends on another card; null: nothing
 */
@JvmRecord
data class SpecialRule(
    val kind: String?, val zone: String?, val reduce: Int, val summoner: String?, val filter: CardFilter?, val reduceBy: Amount?,
    val relative: Selector.Relative?,
) {

    constructor(kind: String?, zone: String?, reduce: Int, summoner: String?, filter: CardFilter?) :
            this(kind, zone, reduce, summoner, filter, null, null)

    /** "esta carta invoca por Ritual…". */
    fun selfSummoner(): Boolean {
        return "self" == summoner
    }

    companion object {
        /** The method this card itself is summoned by. */
        const val OF_THIS_CARD = "ofThisCard"
        const val RITUAL = "ritual"
    }
}
