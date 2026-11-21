package com.drive.drive_manager.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DeckListJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void readsADeckWithoutTheBotFlag() {
        // Shape the web sends when saving a deck: no botDeck.
        DeckList deck = mapper.readValue(
                "{\"deckName\":\"Mazo\",\"cards\":[\"a\",\"b\"],\"deckImage\":\"a\",\"privateDeck\":true}",
                DeckList.class);

        assertEquals("Mazo", deck.getDeckName());
        assertEquals(List.of("a", "b"), deck.getCards());
        assertTrue(deck.isPrivateDeck());
        assertFalse(deck.isBotDeck());
    }

    @Test
    void readsADeckWithNoFlagsAtAll() {
        DeckList deck = mapper.readValue("{\"deckName\":\"Mazo\",\"cards\":[\"a\"]}", DeckList.class);

        assertFalse(deck.isPrivateDeck());
        assertFalse(deck.isBotDeck());
    }

    @Test
    void keepsTheBotFlagWhenSent() {
        DeckList deck = mapper.readValue("{\"deckName\":\"Bot\",\"cards\":[\"a\"],\"botDeck\":true}", DeckList.class);

        assertTrue(deck.isBotDeck());
    }

    @Test
    void readsAdminSettingsWithMissingNumbers() {
        // Same rule for the admin forms: a missing number keeps its default.
        EditionMetadata edition = mapper.readValue(
                "{\"coverFrame\":{\"x\":1},\"boxConfig\":{\"entries\":[{}]}}", EditionMetadata.class);
        ShopOdds odds = mapper.readValue("{\"foil\":0.5}", ShopOdds.class);

        assertEquals(0, edition.getNumberOfCards());
        assertEquals(0.5, odds.getFoil());
        assertEquals(0, odds.getPrism());
    }
}
