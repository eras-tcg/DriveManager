package com.drive.drive_manager.simulator.room;

import java.time.Duration;

/** The rooms' caps and timeouts. */
final class SimulatorLimits {

    private SimulatorLimits() {}

    /** How often the rooms are swept: empty ones closed, owners who left replaced, the bots woken (milliseconds). */
    static final long SWEEP_INTERVAL_MS = 5_000;
    /**
     * A room with nobody connected is closed. It waits this long first, so a reload or a lost connection (the
     * socket drops and comes back) does not close it.
     */
    static final Duration EMPTY_ROOM_TTL = Duration.ofSeconds(30);
    /** An owner disconnected this long has left: another player owns the room. */
    static final long OWNER_GRACE_MS = 15_000;
    /** A player disconnected this long during an online game has left it: the one who stays wins (a reload is shorter). */
    static final long ABANDON_GRACE_MS = 20_000;
    /** Lines of a room's history kept (the oldest are forgotten). */
    static final int EVENT_LOG_MAX = 300;
}
