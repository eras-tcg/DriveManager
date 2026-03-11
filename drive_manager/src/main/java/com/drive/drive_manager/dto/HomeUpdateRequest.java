package com.drive.drive_manager.dto;

import java.util.List;
import lombok.Data;

@Data
public class HomeUpdateRequest {

    private EditionMetadata edition;
    private List<DeckWithPosition> decks;

    public static class DeckWithPosition {

        private DeckList deck;
        private String deckPosition;

        public DeckList getDeck() { 
            return deck; 
        }

        public String getDeckPosition() { 
            return deckPosition; 
        }

        public void setDeck(DeckList deck) { 
            this.deck = deck; 
        }

        public void setDeckPosition(String deckPosition) { 
            this.deckPosition = deckPosition; 
        }
    }
}