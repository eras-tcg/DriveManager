package com.drive.drive_manager.simulator.engine;

/**
 * "Todos tus aliados [filtro] ganan [habilidad] hasta …": it applies to the table, not to the creatures there at the time,
 * so a creature that enters later gets it too (designer ruling 2026-10-30, Príncipe Charles).
 *
 * @param until "turn" (until the end of this turn) or "nextTurn" (until the end of the opponent's next turn)
 */
public record FieldGrant(String controllerId, CardFilter filter, String keyword, String until, String source) {}
