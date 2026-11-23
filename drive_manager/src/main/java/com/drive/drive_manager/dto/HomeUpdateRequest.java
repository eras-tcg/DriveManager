package com.drive.drive_manager.dto;

import java.util.List;
import lombok.Data;

@Data
public class HomeUpdateRequest {

    private EditionMetadata edition;
    private List<DeckWithPosition> decks;
    /** The carousel's news as they must be (all of them, in order); null leaves them as they are. */
    private List<HomeNews> news;

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