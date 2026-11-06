package com.drive.drive_manager.simulator.room;

/**
 * What kind of room is created: a test room (editor: its owner plays both sides), one against the bot (the bot is
 * seated, never in a test room) and whether its result moves the players' rank (never a test room).
 */
public record RoomOptions(boolean editor, boolean bot, boolean ranked) {

    /** A friendly online room. */
    public static final RoomOptions ONLINE = new RoomOptions(false, false, false);
    /** A test room (Sala de pruebas). */
    public static final RoomOptions TEST = new RoomOptions(true, false, false);
    /** A friendly game against the bot. */
    public static final RoomOptions BOT = new RoomOptions(false, true, false);
    /** A ranked online room. */
    public static final RoomOptions RANKED = new RoomOptions(false, false, true);
    /** A ranked game against the bot. */
    public static final RoomOptions RANKED_BOT = new RoomOptions(false, true, true);
}
