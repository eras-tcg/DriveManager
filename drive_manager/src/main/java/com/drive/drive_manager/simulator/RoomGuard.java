package com.drive.drive_manager.simulator;

import com.drive.drive_manager.repository.DeckListRepository;

import java.util.Set;

/**
 * Who may do what in a room, checked before any action (what each player is sent is StateMask's): only the room's
 * players act on it, and an online game is played by the rules, so the board's manual actions are for test rooms
 * and the site's editors.
 */
final class RoomGuard {

    private RoomGuard() {}

    /** What any player can do in an online game: the rules engine's actions and the room's own flow. */
    static final Set<String> RULES_ACTIONS = Set.of(
            "NORMAL_SUMMON", "RESOLVE_PROMPT", "SACRIFICE_CARD", "ATTACK", "PLAY_CARD", "RITUAL_SUMMON", "ACTIVATE_EFFECT",
            "TARGET_CARD", "ENTER_END_PHASE", "END_TURN", "PASS_TURN", "SURRENDER", "FORFEIT", "POST_GAME_CHOICE",
            "JOIN_ROOM", "LEAVE_ROOM", "KICK_PLAYER", "CLOSE_ROOM", "START_GAME", "SET_DECK", "SET_STARTER",
            "SET_FIRST_PLAYER", "SET_READY", "SELECT_STARTER", "CONFIRM_MULLIGAN", "SET_SKIP_OPTIONAL");

    /** By hand a creature's strength changes a few points at a time (the board sends ±1 or undoes what it changed). */
    static final int MAX_STRENGTH_CHANGE = 50;

    /** Why the action is refused before it is applied, or null. {@code manual}: the player may use the manual actions. */
    static String refusal(GameRoom room, String userId, GameAction action, boolean manual) {
        if (!"JOIN_ROOM".equals(action.getType()) && !room.getPlayers().containsKey(userId)) return "No estás en la sala";
        if (!room.isEditor() && !manual && !RULES_ACTIONS.contains(action.getType()))
            return "Esa acción manual solo está disponible en partidas de prueba";
        return null;
    }

    /** A game in course (from the starters' choice to its end). */
    static boolean playing(GameRoom room) {
        GameRoom.RoomStatus s = room.getStatus();
        return s == GameRoom.RoomStatus.SELECTING_STARTER || s == GameRoom.RoomStatus.MULLIGAN || s == GameRoom.RoomStatus.IN_PROGRESS;
    }

    /** A game between two players is going on: from choosing the starters to its end. */
    static boolean inGame(GameRoom room) {
        return room.getPlayers().size() == 2 && playing(room);
    }

    /** A deck the player may use: their own or a public one (in a test room, the rival's side uses its owner's). */
    static boolean usableDeck(DeckListRepository decks, GameRoom room, String userId, String deckId) {
        String owner = RoomService.EDITOR_RIVAL.equals(userId) ? room.getHostId() : userId;
        return decks.findById(deckId).map(d -> !d.isPrivateDeck() || owner != null && owner.equals(d.getUserId())).orElse(false);
    }
}
