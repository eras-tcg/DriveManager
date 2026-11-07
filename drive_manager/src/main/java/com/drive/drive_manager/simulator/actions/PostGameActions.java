package com.drive.drive_manager.simulator.actions;

import com.drive.drive_manager.simulator.engine.GameRandom;
import com.drive.drive_manager.simulator.room.ActionType;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;
import com.drive.drive_manager.simulator.room.RoomHost;

/**
 * After a game: each player's choice (a rematch, back to the pre-room to change decks, or leaving) and the room made
 * ready for the next game (zones emptied, the engine's state cleared, the decks rebuilt and shuffled).
 */
public final class PostGameActions {

    private PostGameActions() {}

    static GameStateUpdate choose(RoomHost host, GameRoom room, String userId, GameAction action) {
        if (room.getStatus() != GameRoom.RoomStatus.POST_GAME)
            return GameStateUpdate.error(room.getRoomId(), "No estás en post-partida");
        String choice = action.getString("choice");
        if (choice == null) return GameStateUpdate.error(room.getRoomId(), "Falta choice");
        // Leaving frees the seat right away: the other player goes back to the pre-room
        if ("exit".equals(choice)) return host.leave(room, userId);
        room.getPostGameChoices().put(userId, choice);
        // A test room: its owner decides for both sides (the rival is not a real player)
        if (room.isEditor()) room.getPlayers().keySet().forEach(id -> room.getPostGameChoices().put(id, choice));
        if (room.getPostGameChoices().size() >= room.getPlayers().size()) {
            // Alone (the rival left): no rematch, back to the pre-room to wait for another ("exit" left already)
            boolean anyChangeDeck = room.getPlayers().size() < 2
                    || room.getPostGameChoices().values().stream().anyMatch("change_deck"::equals);
            if (anyChangeDeck) {
                // Back to the pre-room of the same room, with the decks they had (they may change them)
                reset(room, true);
                room.getPlayers().values().forEach(p -> p.setReady(false));
                room.setStatus(GameRoom.RoomStatus.WAITING);
            } else {
                reset(room, true);
                // A rematch starts right away with the same starters: who goes first is drawn again (rule 3.0.1)
                room.setFirstChoice("random");
                PreRoomActions.startFromPreRoom(room);
            }
        }
        return GameStateUpdate.from(room, ActionType.POST_GAME_CHOICE, userId);
    }

    public static void reset(GameRoom room, boolean keepDecks) {
        room.getPlayers().values().forEach(player -> {
            player.getHand().clear();
            player.getField().clear();
            player.getLifeStack().clear();
            player.getTributeZone().clear();
            player.getDiscardPile().clear();
            player.setResourceCount(0);
            player.setTurnsStarted(0);
            if (keepDecks && player.getDeckId() != null) {
                player.getDeck().clear();
                // The deck as chosen, its finishes included
                player.setDeck(PreRoomActions.deepCopySlots(player.getOriginalDeck()));
                GameRandom.shuffle(player.getDeck(), room.getRandom());
            } else if (!keepDecks) {
                player.getDeck().clear();
            }
        });
        room.setSharedCounter(0);
        room.setCurrentTurnUserId(null);
        room.setFirstPlayerId(null);
        room.setFirstTurn(false);
        room.setStarterAdvantageUserId(null);
        room.setWinnerId(null);
        room.setEndReason(null);
        room.setStartPhasePending(false);
        room.setEndTurnPending(false);
        room.setCurrentPhase("MAIN");
        room.getStarterSelections().clear();
        room.getMulliganReady().clear();
        room.getPostGameChoices().clear();
        // A new game starts with an empty chain, no pending choice and a fresh timeline
        room.getChain().clear();
        room.setPrompt(null);
        room.getTriggerQueue().clear();
        room.getPendingEvents().clear();
        room.setCombat(null);
        room.getUsedThisTurn().clear();
        room.getFieldGrants().clear();
        room.getTimeline().clear();
        // The next game is recorded from its own start
        room.setStartSetup(null);
    }
}
