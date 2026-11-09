package com.drive.drive_manager.simulator.engine

import kotlin.jvm.JvmStatic
import com.drive.drive_manager.simulator.platform.LocalValue
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.GameStateUpdate

/**
 * Who looks at a room as its chain resolves, around one action (per thread): after each step of a link that did
 * something (LINK_STEP) and after each link leaves the chain (LINK_RESOLVED). The server and the offline room send the
 * room as it is then as an update of its own, so the board plays each link (and each part of it: "…; si lo haces, …")
 * when it happens instead of everything at the chain's end. Only that room: the bot's copies and rooms played again
 * (other objects) never call it.
 */
object LinkWatch {

    private class Watch(val room: GameRoom, val onUpdate: (GameRoom, String) -> Unit) {
        /** The room's last event already sent (in an update of the chain, or before the action). */
        var shownSeq: Long = room.eventSeq
    }

    private val current = LocalValue<Watch?> { null }

    /**
     * Runs `block` calling `onUpdate` with `room` and the update's event (LINK_STEP, LINK_RESOLVED) as its chain
     * resolves; then puts back the watch there was.
     */
    @JvmStatic
    fun <T> during(room: GameRoom, onUpdate: (GameRoom, String) -> Unit, block: () -> T): T {
        val before = current.get()
        current.set(Watch(room, onUpdate))
        try {
            return block()
        } finally {
            current.set(before)
        }
    }

    /** A step of `room`'s resolving link did something (recorded events): it is shown before the next step. */
    @JvmStatic
    fun stepped(room: GameRoom) = tell(room, GameStateUpdate.LINK_STEP)

    /**
     * A link of `room` is about to start resolving: what was done since the last update sent (a choice answered just
     * before, as moving up after a special summon, and the draw after it) is sent first, so it is not shown after that
     * link's start.
     */
    @JvmStatic
    fun starting(room: GameRoom) {
        val watch = current.get() ?: return
        if (watch.room === room && room.eventSeq != watch.shownSeq) tell(room, GameStateUpdate.LINK_STEP)
    }

    /** A link of `room` resolved (the engine, right after it leaves the chain). */
    @JvmStatic
    fun resolved(room: GameRoom) = tell(room, GameStateUpdate.LINK_RESOLVED)

    private fun tell(room: GameRoom, event: String) {
        val watch = current.get() ?: return
        if (watch.room !== room || room.headless) return
        watch.shownSeq = room.eventSeq
        watch.onUpdate(room, event)
    }
}
