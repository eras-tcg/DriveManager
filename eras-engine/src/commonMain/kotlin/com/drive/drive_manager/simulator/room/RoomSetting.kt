package com.drive.drive_manager.simulator.room

/**
 * A field of [GameRoom] that belongs to the room, not to the game in it (who plays each side, the bots, their
 * pace, the pause, the room's chance…): kept as it is when the Sala de pruebas travels in time
 * ([TestTimeline], [RoomCopy.restore]). Every other field travels with the game.
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD)
internal annotation class RoomSetting
