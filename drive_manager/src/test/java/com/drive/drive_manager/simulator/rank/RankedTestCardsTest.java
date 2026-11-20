package com.drive.drive_manager.simulator.rank;

import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.simulator.bot.BotPlayer;
import com.drive.drive_manager.simulator.room.DeckLoader;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;
import com.drive.drive_manager.simulator.room.RoomOptions;
import com.drive.drive_manager.simulator.room.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Cards in testing are played in friendly games only: a ranked room refuses a deck that has them. */
@Tag("server")
class RankedTestCardsTest {

    private final RoomService service = new RoomService();

    private GameStateUpdate act(String roomId, String userId, String type, Map<String, Object> payload) {
        return service.applyAction(roomId, userId, BotPlayer.action(type, payload), false);
    }

    @BeforeEach
    void setUp() {
        DeckLoader decks = mock(DeckLoader.class);
        DeckList withTestCards = new DeckList();
        withTestCards.setId("test-deck");
        DeckList legal = new DeckList();
        legal.setId("legal-deck");
        when(decks.deck("test-deck")).thenReturn(Optional.of(withTestCards));
        when(decks.deck("legal-deck")).thenReturn(Optional.of(legal));
        when(decks.hasTestCards(withTestCards)).thenReturn(true);
        when(decks.hasTestCards(legal)).thenReturn(false);
        service.decks = decks;
    }

    @Test
    void aRankedRoomRefusesADeckWithCardsInTesting() {
        GameRoom room = service.createRoom("owner", RoomOptions.RANKED);
        act(room.getRoomId(), "owner", "JOIN_ROOM", Map.of("username", "Ana"));

        GameStateUpdate refused = act(room.getRoomId(), "owner", "SET_DECK", Map.of("deckId", "test-deck"));
        assertThat(refused.getError()).contains("cartas en test");
        assertThat(room.getPlayer("owner").getDeckId()).isNull();
    }

    @Test
    void aFriendlyRoomWithSuchADeckCannotTurnRanked() {
        GameRoom room = service.createRoom("owner", RoomOptions.ONLINE);
        act(room.getRoomId(), "owner", "JOIN_ROOM", Map.of("username", "Ana"));
        room.getPlayer("owner").setDeckId("test-deck");

        assertThat(act(room.getRoomId(), "owner", "SET_RANKED", Map.of("ranked", true)).getError()).contains("cartas en test");
        assertThat(room.isRanked()).isFalse();

        room.getPlayer("owner").setDeckId("legal-deck");
        assertThat(act(room.getRoomId(), "owner", "SET_RANKED", Map.of("ranked", true)).getError()).isNull();
        assertThat(room.isRanked()).isTrue();
    }
}
