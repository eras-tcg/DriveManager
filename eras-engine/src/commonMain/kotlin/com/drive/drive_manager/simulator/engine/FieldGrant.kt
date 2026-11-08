package com.drive.drive_manager.simulator.engine

/**
 * "Todos tus aliados [filtro] ganan [habilidad] hasta …": it applies to the table, not to the creatures there at the time,
 * so a creature that enters later gets it too (designer ruling 2026-10-30, Príncipe Charles).
 *
 * @param until    "turn" (until the end of this turn) or "nextTurn" (until the end of the opponent's next turn)
 * @param exceptId "todos tus otros aliados": the card that gives it, which does not get it; null for all
 */
data class FieldGrant(
    val controllerId: String?,
    val filter: CardFilter?,
    val keyword: String?,
    val until: String?,
    val source: String?,
    val exceptId: String?,
)
