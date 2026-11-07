package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.room.ActionType;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.RoomEngine;

import java.util.Set;

/**
 * The bot's seat in a room, as the rules know it (the server's {@link BotRooms} runs the bot on it): its id, and what
 * the room's owner does for it.
 */
public final class BotSeat {

    /** The bot's seat in a room against it. */
    public static final String BOT = "bot";
    /** What the owner of a room against the bot does for it, with "actAs" = BOT: choosing its deck and starter. */
    static final Set<ActionType> SETUP = java.util.EnumSet.of(ActionType.SET_DECK, ActionType.SET_STARTER);

    private BotSeat() {}

    /** The owner choosing the bot's deck or starter ("actAs" = BOT) in a room against it. */
    public static boolean forBot(GameRoom room, GameAction action) {
        return room.isBot() && BOT.equals(action.getString("actAs")) && SETUP.contains(ActionType.of(action));
    }

    /** The seat the bot plays: its own in a room against it, the test rival's in a test room it plays. */
    public static String seatOf(GameRoom room) {
        return room.isEditor() ? RoomEngine.EDITOR_RIVAL : BOT;
    }
}
