package com.drive.drive_manager.simulator.room

/**
 * What the rules of a room ask of whoever runs it: who joins and leaves, the decks, the bot's and the timeline's
 * settings. The server's [RoomService] answers them from its rooms, Mongo and the players' collections; a game
 * that is only played (the bot's copies, later a game in the browser) refuses them ([gameOnly]).
 */
interface RoomHost {

    fun join(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate

    fun leave(room: GameRoom, userId: String?): GameStateUpdate

    fun kick(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate

    fun close(room: GameRoom, userId: String?): GameStateUpdate

    /** The bot's settings of the room: its level, the test room's bot, its pace and edge, ranked. */
    fun botSetting(type: ActionType, room: GameRoom, userId: String?, action: GameAction): GameStateUpdate

    /** The test room's pause and time travel. */
    fun timeline(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate

    /** The pre-room's choice of a deck. */
    fun setDeck(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate

    /** A card's metadata read again (edited on the site meanwhile). */
    fun refreshCardMetadata(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate

    companion object {
        /** A room that is only played: the lobby and the settings are not available in it. */
        @JvmStatic
        fun gameOnly(): RoomHost {
            return GameOnly
        }
    }

    object GameOnly : RoomHost {

        private fun refused(room: GameRoom): GameStateUpdate {
            return GameStateUpdate.error(room.roomId, "No disponible en esta partida")
        }

        override fun join(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate = refused(room)
        override fun leave(room: GameRoom, userId: String?): GameStateUpdate = refused(room)
        override fun kick(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate = refused(room)
        override fun close(room: GameRoom, userId: String?): GameStateUpdate = refused(room)
        override fun botSetting(type: ActionType, room: GameRoom, userId: String?, action: GameAction): GameStateUpdate = refused(room)
        override fun timeline(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate = refused(room)
        override fun setDeck(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate = refused(room)
        override fun refreshCardMetadata(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate = refused(room)
    }
}
