package com.drive.drive_manager.simulator.room;

/**
 * What the rules of a room ask of whoever runs it: who joins and leaves, the decks, the bot's and the timeline's
 * settings. The server's {@link RoomService} answers them from its rooms, Mongo and the players' collections; a game
 * that is only played (the bot's copies, later a game in the browser) refuses them ({@link #gameOnly()}).
 */
public interface RoomHost {

    GameStateUpdate join(GameRoom room, String userId, GameAction action);

    GameStateUpdate leave(GameRoom room, String userId);

    GameStateUpdate kick(GameRoom room, String userId, GameAction action);

    GameStateUpdate close(GameRoom room, String userId);

    /** The bot's settings of the room: its level, the test room's bot, its pace and edge, ranked. */
    GameStateUpdate botSetting(ActionType type, GameRoom room, String userId, GameAction action);

    /** The test room's pause and time travel. */
    GameStateUpdate timeline(GameRoom room, String userId, GameAction action);

    /** The pre-room's choice of a deck. */
    GameStateUpdate setDeck(GameRoom room, String userId, GameAction action);

    /** A card's metadata read again (edited on the site meanwhile). */
    GameStateUpdate refreshCardMetadata(GameRoom room, String userId, GameAction action);

    /** A room that is only played: the lobby and the settings are not available in it. */
    static RoomHost gameOnly() {
        return GameOnly.INSTANCE;
    }

    final class GameOnly implements RoomHost {
        private static final GameOnly INSTANCE = new GameOnly();

        private GameOnly() {}

        private static GameStateUpdate refused(GameRoom room) {
            return GameStateUpdate.error(room.getRoomId(), "No disponible en esta partida");
        }

        @Override public GameStateUpdate join(GameRoom room, String userId, GameAction action) { return refused(room); }
        @Override public GameStateUpdate leave(GameRoom room, String userId) { return refused(room); }
        @Override public GameStateUpdate kick(GameRoom room, String userId, GameAction action) { return refused(room); }
        @Override public GameStateUpdate close(GameRoom room, String userId) { return refused(room); }
        @Override public GameStateUpdate botSetting(ActionType type, GameRoom room, String userId, GameAction action) { return refused(room); }
        @Override public GameStateUpdate timeline(GameRoom room, String userId, GameAction action) { return refused(room); }
        @Override public GameStateUpdate setDeck(GameRoom room, String userId, GameAction action) { return refused(room); }
        @Override public GameStateUpdate refreshCardMetadata(GameRoom room, String userId, GameAction action) { return refused(room); }
    }
}
