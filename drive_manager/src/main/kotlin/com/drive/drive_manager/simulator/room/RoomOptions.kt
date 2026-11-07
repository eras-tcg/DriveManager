package com.drive.drive_manager.simulator.room

/**
 * What kind of room is created: a test room (editor: its owner plays both sides), one against the bot (the bot is
 * seated, never in a test room) and whether its result moves the players' rank (never a test room).
 */
@JvmRecord
data class RoomOptions(val editor: Boolean, val bot: Boolean, val ranked: Boolean) {

    companion object {
        /** A friendly online room. */
        @JvmField val ONLINE = RoomOptions(false, false, false)
        /** A test room (Sala de pruebas). */
        @JvmField val TEST = RoomOptions(true, false, false)
        /** A friendly game against the bot. */
        @JvmField val BOT = RoomOptions(false, true, false)
        /** A ranked online room. */
        @JvmField val RANKED = RoomOptions(false, false, true)
        /** A ranked game against the bot. */
        @JvmField val RANKED_BOT = RoomOptions(false, true, true)
    }
}
