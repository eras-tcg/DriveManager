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

    /**
     * Seeds where the heuristic bot, asked for both seats, sent an answer the rules refused again and again ("Sus niveles
     * deben sumar 2 o menos") until 3000 actions: a refused answer is never sent twice now (BotPlayer).
     */
    @Test
    fun st3AgainstSt4Seed2() = check("st3", "st4", 2, EXPECTED.getValue("st3-st4-2"))

    @Test
    fun st3AgainstSt4Seed3() = check("st3", "st4", 3, EXPECTED.getValue("st3-st4-3"))

    @Test
    fun st3AgainstSt4Seed5() = check("st3", "st4", 5, EXPECTED.getValue("st3-st4-5"))

    /** The server's bot (look-ahead) on both sides, its time counted in clock readings: the same game on both platforms. */
    @Test
    fun lookaheadSt1AgainstSt2() = check("st1", "st2", 4, EXPECTED.getValue("lookahead-st1-st2-4"), lookahead = true)

    @Test
    fun lookaheadSt4AgainstSt3() = check("st4", "st3", 6, EXPECTED.getValue("lookahead-st4-st3-6"), lookahead = true)

    private fun check(host: String, guest: String, seed: Long, expected: String, lookahead: Boolean = false) {
        val game = if (lookahead) SeededBotGame.playLookahead(host, guest, seed) else SeededBotGame.play(host, guest, seed)
        println("${if (lookahead) "lookahead-" else ""}$host-$guest-$seed: ${game.log.size} actions, ${game.room.status}, winner ${game.room.winnerId}, fingerprint ${game.fingerprint}")
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
            "st3-st4-2" to "2acd6bc9b818ec00",
            "st3-st4-3" to "5901435f268e0cf0",
            "st3-st4-5" to "3ef9c646770dfa01",
            "lookahead-st1-st2-4" to "bdfc9dc780b4914a",
            "lookahead-st4-st3-6" to "50db05a7399bcd48",
        )
    }
}
