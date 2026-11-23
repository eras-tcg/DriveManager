package com.drive.drive_manager.simulator.replay;

import com.drive.drive_manager.simulator.room.CardSlot;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MatchHistoryDeckListTest {

    private static CardSlot card(String id, String name) {
        CardSlot c = new CardSlot();
        c.setCardId(id);
        c.setCardName(name);
        return c;
    }

    @Test
    void theDeckIsKeptAsItsListInTheOrderOfItsFirstCopies() {
        List<Document> list = MatchHistory.deckList(List.of(card("b", "Benzo"), card("a", "Flinc"), card("b", "Benzo"), card("a", "Flinc"), card("b", "Benzo")));
        assertThat(list).extracting(d -> d.getString("cardName")).containsExactly("Benzo", "Flinc");
        assertThat(list).extracting(d -> d.getInteger("count")).containsExactly(3, 2);
        assertThat(list.get(0).getString("cardId")).isEqualTo("b");
    }

    @Test
    void aSeatWithoutDeckKeepsAnEmptyList() {
        assertThat(MatchHistory.deckList(null)).isEmpty();
    }
}
