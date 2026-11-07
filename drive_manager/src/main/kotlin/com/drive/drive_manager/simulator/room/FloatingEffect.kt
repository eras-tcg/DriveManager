package com.drive.drive_manager.simulator.room

/**
 * What an effect gave or took from a card for a while ("pierde 3 de fuerza hasta el final del turno"), with the
 * card it came from, so the card's details can show it. [until]: "turn", "nextTurn" or "attack".
 */
@JvmRecord
data class FloatingEffect(val text: String?, val source: String?, val until: String?, val strength: Int, val keyword: String?)
