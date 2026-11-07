package com.drive.drive_manager.simulator.room;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.simulator.bot.BotRooms;

import java.util.ArrayList;
import java.util.List;

import static com.drive.drive_manager.simulator.actions.PreRoomActions.deepCopySlots;

/**
 * The server's side of the pre-room's decks ({@link RoomHost#setDeck}, {@link RoomHost#refreshCardMetadata}): the
 * decks in Mongo, the player's collection and finishes, the bot's copy of the deck.
 */
final class RoomDecks {

    private RoomDecks() {}

    /** Pre-room: the deck a player will use; its first Iniciador is the starter until another is chosen. */
    static GameStateUpdate setDeck(RoomService service, GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.notInRoom(room);
        if (room.getStatus() != GameRoom.RoomStatus.WAITING) return GameStateUpdate.error(room.getRoomId(), SimMessages.GAME_STARTED);
        String deckId = action.getString("deckId");
        if (deckId == null) return GameStateUpdate.error(room.getRoomId(), "Falta el mazo");
                DeckList deck = service.decks == null ? null : service.decks.deck(deckId).orElse(null);
        if (deck == null || !RoomGuard.usableDeck(deck, room, userId)) return GameStateUpdate.error(room.getRoomId(), SimMessages.DECK_NOT_FOUND);
        List<CardSlot> slots = service.decks.slots(deck);
        if (slots.isEmpty()) return GameStateUpdate.error(room.getRoomId(), SimMessages.DECK_NOT_FOUND);
        // The bot plays the same deck as its own cards (new instances, without the owner's finishes)
        List<CardSlot> forBot = room.isBot() ? DeckLoader.freshCopy(slots) : null;
        // Cards the engine does not run cannot be played; a test room plays them by hand (it is where they are tried)
        if (!room.isEditor() && DeckLoader.unprogrammedIn(slots) != null) return GameStateUpdate.error(room.getRoomId(),
                "Este mazo tiene cartas que todavía no están programadas en el simulador");
        // Only with every copy in the player's collection (a test room plays anything: it is where cards are tried; the bot owns none)
        boolean bot = BotRooms.BOT.equals(userId);
                if (!room.isEditor() && !bot && service.hooks != null && service.hooks.missing(userId, slots) > 0) {
            return GameStateUpdate.error(room.getRoomId(), "No puedes jugar este mazo: te faltan cartas de tu colección");
        }
        if (service.hooks != null && !bot) service.hooks.withFinishes(userId, slots);
        player.setDeckId(deckId);
        player.setDeckName(deck.getDeckName());
        player.setDeck(new ArrayList<>(slots));
        player.setOriginalDeck(deepCopySlots(slots));
        player.setStarterCardId(slots.stream().filter(CardSlot::isStarter).map(CardSlot::getCardId).findFirst().orElse(null));
        player.setReady(false);
        if (room.isBot() && !bot) service.botRooms.mirrorDeck(room, player, forBot);
        return GameStateUpdate.from(room, ActionType.SET_DECK, userId);
    }

    static GameStateUpdate refreshCardMetadata(RoomService service, GameRoom room, String userId, GameAction action) {
        String cardId = action.getString("cardId");
        if (cardId == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.NO_CARD);
        Card meta = service.decks == null ? null : service.decks.metadataOf(cardId);
        if (meta == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.METADATA_NOT_FOUND);
        DeckLoader.refresh(room, cardId, meta);
        return GameStateUpdate.from(room, ActionType.REFRESH_CARD_METADATA, userId);
    }
}
