package com.drive.drive_manager.simulator.engine;

/**
 * An Ascenso waiting for the attack its summoner declared first ("cuando esta carta fuera a ser ascendida: puede declarar
 * ataque"): once everything that reacts to the declaration has resolved, the creature arrives and takes its place.
 */
public record PendingAscension(String playerId, String cardId, String summonerId, String fromZone) {}
