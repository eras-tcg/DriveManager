package com.drive.drive_manager.simulator.engine

/**
 * A card about to leave the field that has a replacement ("cuando esta carta fuera a ser destruida / removida: en su
 * lugar, puedes …", Protección): its owner chooses first. [destroy]: destroyed (else sent to the hand).
 */
data class PendingRemoval(
    val cardId: String?,
    val ownerId: String?,
    val destroy: Boolean,
    val cause: String?,
    val byId: String?,
    val byCard: String?,
    val effectId: String?,
)
