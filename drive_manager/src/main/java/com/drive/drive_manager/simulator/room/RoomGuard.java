package com.drive.drive_manager.simulator.room;

import com.drive.drive_manager.simulator.bot.BotSeat;

/**
 * Who may do what in a room, checked before any action (what each player is sent is StateMask's): only the room's
 * players act on it, and an online game is played by the rules, so the board's manual actions are for test rooms
 * and the site's editors.
 */
public final class RoomGuard {

    private RoomGuard() {}

    /** By hand a creature's strength changes a few points at a time (the board sends ±1 or undoes what it changed). */
    public static final int MAX_STRENGTH_CHANGE = 50;

    /** Why the action is refused before it is applied, or null. {@code manual}: the player may use the manual actions. */
    static String refusal(GameRoom room, String userId, GameAction action, boolean manual) {
        boolean join = ActionType.JOIN_ROOM.is(action);
        if (!join && !room.getPlayers().containsKey(userId)) return SimMessages.NOT_IN_ROOM;
        if (join && !room.getReserved().isEmpty() && !room.getReserved().contains(userId))
            return "Esta sala es de una partida de la cola ranked";
        if (!room.isEditor() && !manual && !ActionType.has(action, ActionType.Flag.RULES))
            return "Esa acción manual solo está disponible en partidas de prueba";
        return null;
    }

    /** A game in course (from the starters' choice to its end). */
    public static boolean playing(GameRoom room) {
        return room.getStatus().isPlaying();
    }

    /** A game between two players is going on: from choosing the starters to its end. */
    public static boolean inGame(GameRoom room) {
        return room.getPlayers().size() == 2 && playing(room);
    }

    /** A deck the player may use: their own or a public one (the rival's side of a test room, and the bot, use its owner's). */
    public static boolean usableDeck(com.drive.drive_manager.dto.DeckList deck, GameRoom room, String userId) {
        String owner = RoomService.EDITOR_RIVAL.equals(userId) || BotSeat.BOT.equals(userId) ? room.getHostId() : userId;
        return !deck.isPrivateDeck() || owner != null && owner.equals(deck.getUserId());
    }
}
