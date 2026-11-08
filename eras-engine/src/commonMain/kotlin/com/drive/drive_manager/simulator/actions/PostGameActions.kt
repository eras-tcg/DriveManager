package com.drive.drive_manager.simulator.actions

import kotlin.jvm.JvmStatic
import com.drive.drive_manager.simulator.engine.GameRandom
import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.GameStateUpdate
import com.drive.drive_manager.simulator.room.RoomHost

/**
 * After a game: each player's choice (a rematch, back to the pre-room to change decks, or leaving) and the room made
 * ready for the next game (zones emptied, the engine's state cleared, the decks rebuilt and shuffled).
 */
object PostGameActions {

    @JvmStatic
    fun choose(host: RoomHost, room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        if (room.status != GameRoom.RoomStatus.POST_GAME)
            return GameStateUpdate.error(room.roomId, "No estás en post-partida")
        val choice = action.getString("choice") ?: return GameStateUpdate.error(room.roomId, "Falta choice")
        // Leaving frees the seat right away: the other player goes back to the pre-room
        if ("exit" == choice) return host.leave(room, userId)
        postGameChoices(room)[userId] = choice
        // A test room: its owner decides for both sides (the rival is not a real player)
        if (room.editor) room.players.keys.forEach { id -> room.postGameChoices[id] = choice }
        if (room.postGameChoices.size >= room.players.size) {
            // Alone (the rival left): no rematch, back to the pre-room to wait for another ("exit" left already)
            val anyChangeDeck = room.players.size < 2
                    || room.postGameChoices.values.any { "change_deck" == it }
            if (anyChangeDeck) {
                // Back to the pre-room of the same room, with the decks they had (they may change them)
                reset(room, true)
                room.players.values.forEach { p -> p.ready = false }
                room.status = GameRoom.RoomStatus.WAITING
            } else {
                reset(room, true)
                // A rematch starts right away with the same starters: who goes first is drawn again (rule 3.0.1)
                room.firstChoice = "random"
                PreRoomActions.startFromPreRoom(room)
            }
        }
        return GameStateUpdate.from(room, ActionType.POST_GAME_CHOICE, userId)
    }

    /** The room's post-game choices, as Java filled them: by a possibly null id. */
    @Suppress("UNCHECKED_CAST")
    private fun postGameChoices(room: GameRoom): MutableMap<String?, String> =
        room.postGameChoices as MutableMap<String?, String>

    @JvmStatic
    fun reset(room: GameRoom, keepDecks: Boolean) {
        room.players.values.forEach { player ->
            player.hand.clear()
            player.field.clear()
            player.lifeStack.clear()
            player.tributeZone.clear()
            player.discardPile.clear()
            player.resourceCount = 0
            player.turnsStarted = 0
            if (keepDecks && player.deckId != null) {
                player.deck.clear()
                // The deck as chosen, its finishes included
                player.deck = PreRoomActions.deepCopySlots(player.originalDeck)
                GameRandom.shuffle(player.deck, room.random)
            } else if (!keepDecks) {
                player.deck.clear()
            }
        }
        room.sharedCounter = 0
        room.currentTurnUserId = null
        room.firstPlayerId = null
        room.firstTurn = false
        room.starterAdvantageUserId = null
        room.winnerId = null
        room.endReason = null
        room.startPhasePending = false
        room.endTurnPending = false
        room.currentPhase = "MAIN"
        room.starterSelections.clear()
        room.mulliganReady.clear()
        room.postGameChoices.clear()
        // A new game starts with an empty chain, no pending choice and a fresh timeline
        room.chain.clear()
        room.prompt = null
        room.triggerQueue.clear()
        room.pendingEvents.clear()
        room.combat = null
        room.usedThisTurn.clear()
        room.fieldGrants.clear()
        room.timeline.clear()
        // The next game is recorded from its own start
        room.startSetup = null
    }
}
