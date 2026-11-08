package com.eras.engine.local

import com.drive.drive_manager.simulator.TestCards
import com.drive.drive_manager.simulator.TestFiles
import com.drive.drive_manager.simulator.platform.Platform
import com.drive.drive_manager.simulator.room.GameRoom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A [LocalMatch] played from a seed sends the same messages on the JVM and on JavaScript (this test runs on both,
 * against the same sums of every message of each game), and behaves as the server towards a refused, an unreadable
 * or a silent action. (That each message is the server's own, byte for byte, is drive_manager's LocalMatchParityTest.)
 */
class LocalMatchTest {

    @Test
    fun st1MirrorSeed1() = check("st1", null, 1, null)

    @Test
    fun st2AgainstSt3Seed2() = check("st2", "st3", 2, null)

    @Test
    fun st4AgainstSt1Seed3WithARefusedAction() = check("st4", "st1", 3, null, illegal = true)

    @Test
    fun st3AgainstSt4Seed5NormalBot() = check("st3", "st4", 5, "normal")

    private fun check(deck: String, botDeck: String?, seed: Long, level: String?, illegal: Boolean = false) {
        val name = "$deck-${botDeck ?: deck}-$seed-${level ?: "easy"}"
        val game = LocalGames.play(deck, botDeck, seed, level ?: "easy", illegal)
        println("$name: ${game.messages.size} messages, ${game.sent.size} sent (${game.refused} refused), ${game.status}, digest ${game.digest}")
        TestFiles.dump("$name.txt", game.messages.joinToString("\n"))
        assertNull(game.match.lastError())
        assertEquals(GameRoom.RoomStatus.POST_GAME, game.status, "the game ends")
        assertEquals(EXPECTED[name], game.digest, name)
    }

    @Test
    fun aRefusedActionIsAnErrorToThePlayerAlone() {
        TestCards.install()
        val match = LocalMatch(LocalGames.config(9))
        assertEquals(1, match.send(LocalGames.body("JOIN_ROOM", mapOf("username" to "Ana"))).size)
        val refused = match.send(LocalGames.body("END_TURN", mapOf()))
        assertEquals(1, refused.size)
        assertTrue(refused[0].startsWith("{\"activatable\":null,\"actorId\":\"local-player\","), refused[0])
        assertTrue(refused[0].contains("\"error\":\""), refused[0])
        assertTrue(refused[0].contains("\"players\":null"), "an error carries no room: " + refused[0])
    }

    @Test
    fun anUnreadableBodySendsNothing() {
        TestCards.install()
        val match = LocalMatch(LocalGames.config(9))
        assertEquals(0, match.send("not json").size)
        assertNotNull(match.lastError())
        assertEquals(0, match.send("{\"type\":\"END_TURN\",\"payload\":[1]}").size)
        assertEquals(0, match.send("[]").size)
        assertEquals(1, match.send(LocalGames.body("JOIN_ROOM", mapOf("username" to "Ana"))).size)
        assertNull(match.lastError())
    }

    @Test
    fun theBotsPaceSendsNothingAndShowsInTheNextUpdate() {
        TestCards.install()
        val match = LocalMatch(LocalGames.config(9))
        match.send(LocalGames.body("JOIN_ROOM", mapOf("username" to "Ana")))
        assertEquals(0, match.send(LocalGames.body("SET_BOT_DELAY", mapOf("ms" to 250))).size)
        assertEquals(250, match.botPauseMs())
        val next = match.send(LocalGames.body("SET_DECK", mapOf("deckId" to "deck-st1")))
        assertTrue(next.single().contains("\"botDelayMs\":250"))
        // The bot mirrors the deck and confirms it is ready
        val bot = match.step()!!
        assertTrue(bot.single().contains("\"event\":\"SET_READY\",") && bot.single().contains("\"actorId\":\"bot\""), bot.single())
        assertNull(match.step())
    }

    @Test
    fun actionsAreReadAsJacksonReadsThem() {
        val a = ActionJson.read("""{"type":"NORMAL_SUMMON","payload":{"instanceId":"c1","x":12.5,"y":75,"n":3000000000,"l":[1,"a",null],"o":{"k":true}},"extra":1}""")
        assertEquals("NORMAL_SUMMON", a.type)
        val p = a.payload!!
        assertEquals(12.5, p["x"])
        assertEquals(75, p["y"])
        assertEquals(3000000000L, p["n"])
        assertEquals(listOf(1, "a", null), p["l"])
        assertEquals(mapOf("k" to true), p["o"])
        assertNull(ActionJson.read("""{"type":"END_TURN"}""").payload)
    }

    @Test
    fun doublesAreWrittenAsJavaWritesThem() {
        val cases = mapOf(
            50.0 to "50.0", 75.0 to "75.0", 12.5 to "12.5", 0.1 to "0.1", 0.001 to "0.001", 1.0E-4 to "1.0E-4",
            1.0E7 to "1.0E7", 9999999.0 to "9999999.0", 123456.789 to "123456.789", 1.0 / 3 to "0.3333333333333333",
            -2.5 to "-2.5", -0.0 to "-0.0", 0.0 to "0.0", 2.5E10 to "2.5E10", 1.2345E-7 to "1.2345E-7", 1.0E21 to "1.0E21",
            100.0 to "100.0", 0.30000000000000004 to "0.30000000000000004",
        )
        for ((value, text) in cases) assertEquals(text, Platform.doubleText(value), "$value")
    }

    companion object {
        /** Taken on the JVM; JavaScript must give the same. */
        val EXPECTED = mapOf(
            "st1-st1-1-easy" to "7d9b9d3e9ad86f43",
            "st2-st3-2-easy" to "c6cf9697f7b23973",
            "st4-st1-3-easy" to "23f5e9cc0fd10912",
            "st3-st4-5-normal" to "f384af0206de9418",
        )
    }
}
