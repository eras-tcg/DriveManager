package com.eras.engine.local

import com.drive.drive_manager.simulator.platform.Clocks
import com.drive.drive_manager.simulator.room.GameRoom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

/**
 * A game against the bot played in the browser, driven exactly as the board drives the server: it takes the bodies the
 * board publishes to `/app/room/{id}/action` and gives back the JSON texts the server would push to that player's
 * `/user/queue/room/{id}`, the same messages in the same order (the same shapes, field names and order, and masking).
 *
 * Made from a JSON configuration (every member optional but the decks the player will choose):
 * ```
 * {"seed": "42",                 // the game's chance and the bot's (a decimal string: 64 bits); default "0"
 *  "decks": {"<deckId>": <deck>}, // what SET_DECK {"deckId"} finds (LocalDecks); the bot plays the player's by default
 *  "botLevel": "normal",         // easy | normal | hard or a named model (BotCatalog); also SET_BOT_LEVEL in the pre-room
 *  "userId": "local-player", "avatar": null, "playmat": null, "roomId": "local", // the player's name: JOIN_ROOM's
 *  "time": null,                  // epoch millis written on every history line and event ("ts"); null: the clock's
 *  "botClock": "ticks",           // "ticks": the bot's time budgets counted in clock readings (the same game on any
 *  "tickNanos": 1000000}          //   machine); "system": real time, as the server's bot (a slower machine thinks less)
 * ```
 * The room exists as soon as it is made, with the bot seated (as the server's `POST /api/simulator/rooms?bot=true`): the
 * board then sends `JOIN_ROOM` as it does online.
 *
 * Threading: one thread (a Web Worker), no locks, nothing runs on its own. [send] applies one action of the player and
 * returns at once; the bot moves only when asked, one move per [step] (as the server's bot moves after a pause), until
 * [step] says it has nothing to do. A worker posts what [send] returns, then calls [step] every [botPauseMs] until it
 * returns null (and again after the player's next action); [playBot] makes all the bot's moves at once.
 *
 * Exceptions never leave it: a body the server could not read, or a failure inside the engine, sends nothing (the
 * server drops the message as well) and is told by [lastError].
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
class LocalMatch(config: String) {

    internal val room: LocalRoom
    private val clocks: MatchClocks

    /** The room's id in the messages ("local" unless the configuration names another). */
    val roomId: String
    /** The player's id in the messages ("local-player" unless the configuration names another). */
    val userId: String

    private var failure: String? = null
    /** How many times the room's game has reached the post-game (a rematch plays another game in the same room). */
    private var ended = 0

    /** What went wrong in the last [send] or [step] (a body that could not be read, a failure inside the engine), or null. */
    fun lastError(): String? = failure

    init {
        val c = Json.parseToJsonElement(config) as? JsonObject ?: throw IllegalArgumentException("The configuration is not a JSON object")
        val seed = (string(c, "seed") ?: "0").toLong()
        roomId = string(c, "roomId") ?: DEFAULT_ROOM
        userId = string(c, "userId") ?: DEFAULT_USER
        val ticks = (string(c, "tickNanos") ?: DEFAULT_TICK_NANOS.toString()).toLong()
        val botClock = when (string(c, "botClock") ?: "ticks") {
            "ticks" -> Clocks.ticks(ticks)
            "system" -> null
            else -> throw IllegalArgumentException("botClock: \"ticks\" or \"system\"")
        }
        clocks = MatchClocks(string(c, "time")?.toLong(), botClock)
        room = clocks.run {
            LocalRoom(userId, string(c, "avatar"), string(c, "playmat"), roomId, seed,
                LocalDecks(c["decks"] as? JsonObject), string(c, "botLevel"), botSeed(seed))
        }
    }

    /**
     * The player's action, the body the board publishes (`{"type": "…", "payload": {…}}`): the messages the server would
     * send them for it, as JSON texts, in order (one, or none: a refused `SET_BOT_DELAY` sends its error, an accepted one
     * nothing; an unreadable body nothing). The bot does not move here: see [step].
     */
    fun send(action: String): Array<String> {
        failure = null
        val parsed = try {
            ActionJson.read(action)
        } catch (e: IllegalArgumentException) {
            failure = "Unreadable action: " + e.message
            return emptyArray()
        }
        return try {
            counted { clocks.run { room.send(parsed).map { UpdateJson.text(it) } }.toTypedArray() }
        } catch (e: Throwable) {
            failure = "The engine failed on " + parsed.type + ": " + e
            emptyArray()
        }
    }

    /** Runs a step of the game, counting the post-games it reaches. */
    private fun <T> counted(block: () -> T): T {
        val before = room.room.status
        val out = block()
        if (before != GameRoom.RoomStatus.POST_GAME && room.room.status == GameRoom.RoomStatus.POST_GAME) ended++
        return out
    }

    /**
     * One move of the bot: the messages the player is sent of it (one, or none when it is not theirs to see), or null when
     * the bot has nothing to do now (it waits for the player, or the room is closed).
     */
    fun step(): Array<String>? {
        return try {
            counted { clocks.run { room.botMove()?.map { UpdateJson.text(it) } }?.toTypedArray() }
        } catch (e: Throwable) {
            failure = "The bot failed: $e"
            null
        }
    }

    /** Every move the bot has now ([step] until it has none, at most [MAX_BOT_MOVES]), their messages in order. */
    fun playBot(): Array<String> {
        val out = ArrayList<String>()
        for (i in 0 until MAX_BOT_MOVES) out.addAll(step() ?: break)
        return out.toTypedArray()
    }

    /** How long the server's bot would wait before its next move, in ms (the pace chosen in the room, else its own). */
    fun botPauseMs(): Int = room.botPauseMs().toInt()

    /** The room's status now ("WAITING", "MULLIGAN", "IN_PROGRESS", "POST_GAME", "FINISHED"…). */
    fun status(): String = room.room.status.name

    /**
     * The game as it stands, as JSON: `{"status", "gamesEnded", "winnerId", "endReason", "turns", "digest"}`. `gamesEnded`:
     * how many times the room reached the post-game (a rematch plays another game in the room); `digest`: [MatchDigest],
     * the sum of the game without the clock's times (the same game gives the same digest on the JVM and in the browser).
     * What an offline game claims when it is sent to the server, which plays it again and compares.
     */
    fun result(): String {
        val r = room.room
        return JsonObject(linkedMapOf(
            "status" to JsonPrimitive(r.status.name),
            "gamesEnded" to JsonPrimitive(ended),
            "winnerId" to (r.winnerId?.let { JsonPrimitive(it) } ?: JsonNull),
            "endReason" to (r.endReason?.let { JsonPrimitive(it) } ?: JsonNull),
            "turns" to JsonPrimitive(MatchDigest.turns(r)),
            "digest" to JsonPrimitive(MatchDigest.of(r)),
        )).toString()
    }

    companion object {
        const val DEFAULT_ROOM = "local"
        const val DEFAULT_USER = "local-player"
        /** One reading of the bot's clock in "ticks" mode: a millisecond (a budget of 2000 ms lasts 2000 readings). */
        const val DEFAULT_TICK_NANOS = 1_000_000
        /** The bot's moves in a row at most (`BotRooms.playNow`'s limit). */
        const val MAX_BOT_MOVES = 5000

        /** The seed of the bot's own chance, from the game's (BotMatch's second seat: seed × 31 + 2). */
        internal fun botSeed(seed: Long): Long = seed * 31 + 2

        private fun string(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    }
}

/**
 * The room a [LocalMatch] plays, for the JVM (drive_manager plays offline games again and counts them as the server
 * counts its own games: missions, activity log). Not for the browser: the board only reads the messages.
 */
object LocalMatchRoom {
    @kotlin.jvm.JvmStatic
    fun of(match: LocalMatch): GameRoom = match.room.room
}
