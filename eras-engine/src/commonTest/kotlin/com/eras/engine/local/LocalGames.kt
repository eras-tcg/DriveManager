package com.eras.engine.local

import com.drive.drive_manager.simulator.GameFingerprint
import com.drive.drive_manager.simulator.TestCards
import com.drive.drive_manager.simulator.TestFiles
import com.drive.drive_manager.simulator.bot.HeuristicBot
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Whole games of a [LocalMatch] driven as the board drives it: the player's actions chosen by a seeded heuristic bot (on
 * the room it plays in: a test driver, not a player) and sent as the board's JSON bodies, the house bot pumped with
 * [LocalMatch.step] after each one. Every message the player receives is kept, in order.
 */
object LocalGames {

    const val MAX_ACTIONS = 3000

    class Result(val match: LocalMatch, val messages: List<String>, val sent: List<String>, val refused: Int) {
        val status: GameRoom.RoomStatus get() = match.room.room.status
        /** All the messages summed up (FNV-1a 64 over their UTF-8 text, separated by new lines). */
        val digest: String get() = GameFingerprint.digest(messages.joinToString("\n"))
    }

    /** The starter decks of the build (`starter-decks.json`): LocalMatch's "decks". */
    val starterDecks: JsonObject by lazy { Json.parseToJsonElement(TestFiles.text("ERAS_STARTER_DECKS")).jsonObject["decks"]!!.jsonObject }

    /** A tick of the bot's clock in the tests: 20 ms (a 2-second budget is 100 readings), so a game takes seconds. */
    const val TICK_NANOS = 20_000_000L

    fun config(seed: Long, extra: String = ""): String =
        """{"seed":"$seed","time":0,"botClock":"ticks","tickNanos":"$TICK_NANOS","decks":$starterDecks$extra}"""

    /**
     * A game from a seed: the player joins, chooses `deck` (and `botDeck` for the bot, else it mirrors theirs), then plays
     * until the game is over (both choices of the post-game made, when `rematch`), or [MAX_ACTIONS].
     */
    fun play(deck: String, botDeck: String?, seed: Long, level: String? = null, illegal: Boolean = false): Result {
        TestCards.install()
        val match = LocalMatch(config(seed, if (level == null) "" else ""","botLevel":"$level""""))
        val me = match.userId
        val messages = ArrayList<String>()
        val sent = ArrayList<String>()
        var refused = 0
        fun send(type: String, payload: Map<String, Any?>): Array<String> {
            val body = body(type, payload)
            sent.add(body)
            val out = match.send(body)
            messages.addAll(out)
            messages.addAll(match.playBot())
            return out
        }
        send("JOIN_ROOM", linkedMapOf("deckId" to null, "username" to "Ana"))
        send("SET_DECK", linkedMapOf("deckId" to "deck-$deck"))
        if (botDeck != null) send("SET_DECK", linkedMapOf("deckId" to "deck-$botDeck", "actAs" to "bot"))
        // A move the rules refuse: the server answers it with an error, to the player alone
        if (illegal) send("END_TURN", linkedMapOf())
        val player = HeuristicBot()
        player.seed(seed * 31 + 1)
        var actions = 0
        val room = match.room.room
        while (actions < MAX_ACTIONS && room.status != GameRoom.RoomStatus.POST_GAME && match.room.open) {
            val action: GameAction = player.next(room, me) ?: break
            actions++
            val error = send(action.type!!, action.payload ?: linkedMapOf()).firstOrNull { it.contains("\"error\":\"") && !it.contains("\"error\":null") }
            if (error != null) {
                refused++
                player.refused(action, error)
            } else {
                player.played(room, action)
            }
        }
        return Result(match, messages, sent, refused)
    }

    /** The board's body for an action: `{"type": …, "payload": {…}}` (numbers, texts, lists as JSON). */
    fun body(type: String, payload: Map<String, Any?>): String =
        JacksonText.write(JsonObject(linkedMapOf("type" to UpdateJson.value(type), "payload" to UpdateJson.value(payload))))
}
