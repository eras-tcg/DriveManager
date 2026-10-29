package com.drive.drive_manager.simulator.engine;

/**
 * A card about to leave the field that has a replacement ("cuando esta carta fuera a ser destruida / removida: en su
 * lugar, puedes …", Protección): its owner chooses first. {@code destroy}: destroyed (else sent to the hand).
 */
public record PendingRemoval(String cardId, String ownerId, boolean destroy, String cause, String byId, String byCard, String effectId) {}
