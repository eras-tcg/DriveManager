package com.drive.drive_manager.simulator.room;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A field of {@link GameRoom} that belongs to the room, not to the game in it (who plays each side, the bots, their
 * pace, the pause, the room's chance…): kept as it is when the Sala de pruebas travels in time
 * ({@link TestTimeline}, {@link RoomCopy#restore}). Every other field travels with the game.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@interface RoomSetting {
}
