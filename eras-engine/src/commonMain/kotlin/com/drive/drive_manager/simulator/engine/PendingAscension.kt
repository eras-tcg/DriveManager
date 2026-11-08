package com.drive.drive_manager.simulator.engine

/**
 * An Ascenso waiting for the attack its summoner declared first ("cuando esta carta fuera a ser ascendida: puede declarar
 * ataque"): once everything that reacts to the declaration has resolved, the creature arrives and takes its place.
 */
data class PendingAscension(val playerId: String?, val cardId: String?, val summonerId: String?, val fromZone: String?)
