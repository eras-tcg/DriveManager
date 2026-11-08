package com.drive.drive_manager.simulator

import com.drive.drive_manager.simulator.room.GameRoom
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Whole bot-against-bot games from a seed, through the core, with the cards of the build's bundle: they reach their end, and
 * their fingerprint ([GameFingerprint]: the final state, the history and the actions) is the same on the JVM and on
 * JavaScript (this test runs on both, against the same expected sums).
 */
class SeededBotGameTest {

    @Test
    fun st1AgainstSt2() = check("st1", "st2", 11, EXPECTED.getValue("st1-st2-11"))

    @Test
    fun st2AgainstSt3() = check("st2", "st3", 3, EXPECTED.getValue("st2-st3-3"))

    /** With answers the rules refuse on the way (the bot tries again). */
    @Test
    fun st1AgainstSt3() = check("st1", "st3", 2, EXPECTED.getValue("st1-st3-2"))

    @Test
    fun st4AgainstSt1() = check("st4", "st1", 1, EXPECTED.getValue("st4-st1-1"))

    private fun check(host: String, guest: String, seed: Long, expected: String) {
        val game = SeededBotGame.play(host, guest, seed)
        println("$host-$guest-$seed: ${game.log.size} actions, ${game.room.status}, winner ${game.room.winnerId}, fingerprint ${game.fingerprint}")
        assertEquals(GameRoom.RoomStatus.POST_GAME, game.room.status, "the game ends; its last actions: " + game.log.takeLast(12).joinToString("\n"))
        assertEquals(expected, game.fingerprint, "$host-$guest-$seed")
    }

    companion object {
        /** Taken on the JVM; JavaScript must give the same. */
        val EXPECTED = mapOf(
            "st1-st2-11" to "88b0de8df414a174",
            "st2-st3-3" to "65b8b292570e0e87",
            "st1-st3-2" to "fec3a8786e3dc958",
            "st4-st1-1" to "8a0b5311fea31615",
        )
    }
}
