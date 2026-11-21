package com.drive.drive_manager.simulator.bot

import com.drive.drive_manager.simulator.engine.JavaText
import com.drive.drive_manager.simulator.platform.Platform
import kotlin.jvm.JvmStatic
import com.drive.drive_manager.simulator.room.GameRoom
import kotlin.random.Random

/**
 * The names the bot plays under in a ranked game, so it reads as a player and not as a level: one line each of the
 * resource [RESOURCE] (# starts a comment). Without the resource (a game in the browser, a test), the level's name.
 */
object BotNames {

    internal const val RESOURCE = "/bot/names.txt"

    private val LINE_BREAK = Platform.regex("\\r\\n|[\\n\\u000B\\u000C\\r\\u0085\\u2028\\u2029]")

    @JvmStatic
    val NAMES: List<String> = read()

    private fun read(): List<String> {
        val text = BotResourceSources.current().text(RESOURCE, "Cannot read the bot's names $RESOURCE") ?: return emptyList()
        return JavaText.split(LINE_BREAK, text).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.distinct()
    }

    /**
     * A name for the room's rival now, other than `avoid` (the owner's own, the last one) when there is another; null
     * without names. Drawn from the room's id and moment (its version), not from the game's chance: the same room
     * names it alike on the server and in the browser, and the game's draws stay as they were.
     */
    @JvmStatic
    fun pick(room: GameRoom, vararg avoid: String?): String? {
        val skip = avoid.filterNotNull().map { it.lowercase() }.toSet()
        val choices = NAMES.filter { it.lowercase() !in skip }.ifEmpty { NAMES }
        if (choices.isEmpty()) return null
        val seed = ((room.roomId ?: "") + ":" + room.version).hashCode()
        return choices[Random(seed).nextInt(choices.size)]
    }
}
