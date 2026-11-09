package com.drive.drive_manager.simulator.engine

import kotlin.jvm.JvmStatic
import com.drive.drive_manager.simulator.platform.LocalValue
import com.drive.drive_manager.simulator.room.GameRoom

/**
 * Who looks at a room each time one of its chain links finishes resolving, around one action (per thread). The server
 * and the offline room send the room as it is then as an update of its own, so the board plays what each link did
 * when it resolves instead of everything at the chain's end. Only that room: the bot's copies and rooms played again
 * (other objects) never call it.
 */
object LinkWatch {

    private class Watch(val room: GameRoom, val onResolved: (GameRoom) -> Unit)

    private val current = LocalValue<Watch?> { null }

    /** Runs `block` calling `onResolved` with `room` after each of its links resolves; then puts back the watch there was. */
    @JvmStatic
    fun <T> during(room: GameRoom, onResolved: (GameRoom) -> Unit, block: () -> T): T {
        val before = current.get()
        current.set(Watch(room, onResolved))
        try {
            return block()
        } finally {
            current.set(before)
        }
    }

    /** A link of `room` resolved (the engine, right after it leaves the chain). */
    @JvmStatic
    fun resolved(room: GameRoom) {
        val watch = current.get() ?: return
        if (watch.room === room && !room.headless) watch.onResolved(room)
    }
}
