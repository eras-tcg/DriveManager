package com.drive.drive_manager.simulator

import com.drive.drive_manager.simulator.bot.BotCatalog
import com.drive.drive_manager.simulator.bot.BotPlayer
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import kotlin.test.Test

/** How long each decision of the server's bots takes on the real clock (run by hand: BOT_TIMING=1). */
class BotTimingRun {

    private class Timed(val inner: BotPlayer, val times: MutableList<Pair<Long, String>>) : BotPlayer() {
        override fun next(room: GameRoom, me: String?): GameAction? {
            val t = System.nanoTime()
            val a = inner.next(room, me)
            val ms = (System.nanoTime() - t) / 1_000_000
            if (a != null) times.add(ms to (a.type.toString() + " " + room.currentPhase))
            return a
        }
        override fun seed(seed: Long) = inner.seed(seed)
        override fun played(room: GameRoom, action: GameAction) = inner.played(room, action)
        override fun refused(action: GameAction, error: String?) = inner.refused(action, error)
    }

    @Test
    fun timing() {
        if (System.getenv("BOT_TIMING") == null) return
        TestCards.install()
        val level = System.getenv("BOT_TIMING_LEVEL") ?: "guiado"
        for ((h, g, seed) in listOf(Triple("st1", "st2", 3L), Triple("st3", "st4", 5L))) {
            val times = ArrayList<Pair<Long, String>>()
            val host = Timed(BotCatalog.botAt(level), times).also { it.seed(seed * 31 + 1) }
            val guest = Timed(BotCatalog.botAt(level), times).also { it.seed(seed * 31 + 2) }
            val t = System.nanoTime()
            val r = SeededBotGame.play(h, g, seed, mapOf("host" to host, "guest" to guest))
            val all = times.map { it.first }.sorted()
            println("TIMING $level $h-$g: ${r.log.size} actions, ${(System.nanoTime() - t) / 1_000_000_000}s, decisions=${all.size}, " +
                "p50=${all[all.size / 2]} p95=${all[all.size * 95 / 100]} max=${all.last()} over3s=${all.count { it > 3000 }}")
            println("TIMING slowest: " + times.sortedByDescending { it.first }.take(8).joinToString { "${it.first}ms ${it.second}" })
        }
    }
}
