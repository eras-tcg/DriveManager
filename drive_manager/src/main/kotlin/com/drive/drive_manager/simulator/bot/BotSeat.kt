package com.drive.drive_manager.simulator.bot

import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.RoomEngine

/**
 * The bot's seat in a room, as the rules know it (the server's [BotRooms] runs the bot on it): its id, and what
 * the room's owner does for it.
 */
object BotSeat {

    /** The bot's seat in a room against it. */
    const val BOT = "bot"
    /** What the owner of a room against the bot does for it, with "actAs" = BOT: choosing its deck and starter. */
    @JvmField
    internal val SETUP: Set<ActionType> = java.util.EnumSet.of(ActionType.SET_DECK, ActionType.SET_STARTER)

    /** The owner choosing the bot's deck or starter ("actAs" = BOT) in a room against it. */
    @JvmStatic
    fun forBot(room: GameRoom, action: GameAction): Boolean {
        return room.bot && BOT == action.getString("actAs") && SETUP.contains(ActionType.of(action))
    }

    /** The seat the bot plays: its own in a room against it, the test rival's in a test room it plays. */
    @JvmStatic
    fun seatOf(room: GameRoom): String {
        return if (room.editor) RoomEngine.EDITOR_RIVAL else BOT
    }
}
