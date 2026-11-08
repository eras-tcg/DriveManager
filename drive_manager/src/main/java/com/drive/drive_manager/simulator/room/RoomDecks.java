package com.drive.drive_manager.simulator.room;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.DeckList;

import java.util.List;

/**
 * The server's side of the pre-room's decks ({@link RoomHost#setDeck}, {@link RoomHost#refreshCardMetadata}): the rules
 * are the room session's ({@link DeckChoice}, common with the game in the browser); here are the decks in Mongo, the
 * player's collection and finishes, and the server's card instance ids.
 */
final class RoomDecks {

    private RoomDecks() {}

    /** Pre-room: the deck a player will use; its first Iniciador is the starter until another is chosen. */
    static GameStateUpdate setDeck(RoomService service, GameRoom room, String userId, GameAction action) {
        return DeckChoice.setDeck(room, userId, action, new Shelf(service));
    }

    static GameStateUpdate refreshCardMetadata(RoomService service, GameRoom room, String userId, GameAction action) {
        return DeckChoice.refreshCardMetadata(room, userId, action, new Shelf(service));
    }

    /** The decks as the server has them (none in tests: no deck is found). */
    private record Shelf(RoomService service) implements DeckChoice.Shelf {

        @Override
        public DeckChoice.Found find(String deckId) {
            DeckList deck = service.decks == null ? null : service.decks.deck(deckId).orElse(null);
            if (deck == null) return null;
            return new DeckChoice.Found(deck.getDeckName(), deck.isPrivateDeck(), deck.getUserId(), () -> service.decks.slots(deck));
        }

        @Override
        public String nextInstanceId() {
            return DeckLoader.newInstanceId();
        }

        /** Only with every copy in the player's collection (none in tests: anything is played). */
        @Override
        public String refusal(String userId, List<CardSlot> slots) {
            return service.hooks != null && service.hooks.missing(userId, slots) > 0
                    ? "No puedes jugar este mazo: te faltan cartas de tu colección" : null;
        }

        @Override
        public void withFinishes(String userId, List<CardSlot> slots) {
            if (service.hooks != null) service.hooks.withFinishes(userId, slots);
        }

        @Override
        public boolean refresh(GameRoom room, String cardId) {
            Card meta = service.decks == null ? null : service.decks.metadataOf(cardId);
            if (meta == null) return false;
            DeckLoader.refresh(room, cardId, meta);
            return true;
        }
    }
}
