package com.drive.drive_manager.simulator.room

import com.drive.drive_manager.simulator.platform.Platform
import com.drive.drive_manager.simulator.platform.Duration


/** The rooms' caps and timeouts. */
internal object SimulatorLimits {

    /** How often the rooms are swept: empty ones closed, owners who left replaced, the bots woken (milliseconds). */
    const val SWEEP_INTERVAL_MS: Long = 5_000
    /**
     * A room with nobody connected is closed. It waits this long first, so a reload or a lost connection (the
     * socket drops and comes back) does not close it.
     */
    @JvmField val EMPTY_ROOM_TTL: Duration = Platform.seconds(30)
    /** An owner disconnected this long has left: another player owns the room. */
    const val OWNER_GRACE_MS: Long = 15_000
    /** A player disconnected this long during an online game has left it: the one who stays wins (a reload is shorter). */
    const val ABANDON_GRACE_MS: Long = 20_000
    /** Lines of a room's history kept (the oldest are forgotten). */
    const val EVENT_LOG_MAX: Int = 300
}
