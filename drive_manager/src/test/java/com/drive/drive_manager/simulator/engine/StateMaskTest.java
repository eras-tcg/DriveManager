package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.GameStateUpdate;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.StateMask;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Each player is sent only what they may see of the room (found in the manual run of 2026-11-02). */
class StateMaskTest {

    private GameRoom room;
    private PlayerState host;
    private PlayerState guest;

    @BeforeEach
    void setUp() {
        TestTable table = TestTable.inProgress(8, 3);
        room = table.room();
        host = table.host();
        guest = table.guest();
        host.getHand().add(card("mine", "Mi Carta", 1, 1, "R"));
        guest.getHand().add(card("theirs", "Su Carta", 1, 1, "R"));
        guest.getOriginalDeck().add(card("list", "En Su Lista", 1, 1, "R"));
        guest.getLifeStack().add(card("life", "Su Vida", 1, 1, "R"));
        onField(guest, card("up", "Boca Arriba", 1, 1, "R"));
    }

    private JsonNode seenBy(String viewer) {
        return (JsonNode) StateMask.forViewer(room, GameStateUpdate.from(room, "TEST", "host"), viewer);
    }

    private static JsonNode inZone(JsonNode zone, String id) {
        for (JsonNode c : zone) if (id.equals(c.path("instanceId").asString())) return c;
        return null;
    }

    @Test
    void theOpponentsHandIsSentWithoutItsCards() {
        JsonNode theirs = inZone(seenBy("host").path("players").path("guest").path("hand"), "theirs");
        assertThat(theirs.path("instanceId").asString()).isEqualTo("theirs");
        assertThat(theirs.path("cardName").isNull()).isTrue();
        assertThat(theirs.path("imageUrl").isNull()).isTrue();
        assertThat(theirs.path("colors")).isEmpty();
        assertThat(theirs.path("faceDown").asBoolean()).isTrue();
        // Your own hand, you see
        assertThat(inZone(seenBy("host").path("players").path("host").path("hand"), "mine").path("cardName").asString()).isEqualTo("Mi Carta");
    }

    @Test
    void decksFaceDownLivesAndTheOpponentsDecklistAreHidden() {
        JsonNode seen = seenBy("host");
        for (JsonNode c : seen.path("players").path("host").path("deck")) assertThat(c.path("cardName").isNull()).isTrue();
        assertThat(seen.path("players").path("host").path("deck")).hasSize(3);
        assertThat(inZone(seen.path("players").path("guest").path("lifeStack"), "life").path("cardName").isNull()).isTrue();
        assertThat(seen.path("players").path("guest").path("originalDeck")).isEmpty();
        // What is face up on the field is public
        assertThat(inZone(seen.path("players").path("guest").path("field"), "up").path("cardName").asString()).isEqualTo("Boca Arriba");
    }

    @Test
    void theCardsOfYourOwnChoiceAreShownEvenFromTheDeck() {
        Prompt prompt = new Prompt();
        prompt.setId("p1");
        prompt.setPlayerId("host");
        prompt.setCards(List.of(host.getDeck().get(0)));
        room.setPrompt(prompt);
        String id = host.getDeck().get(0).getInstanceId();
        assertThat(inZone(seenBy("host").path("prompt").path("cards"), id).path("cardName").asString()).isEqualTo("Carta 1");
        assertThat(inZone(seenBy("guest").path("prompt").path("cards"), id).path("cardName").isNull()).isTrue();
    }

    @Test
    void theCardsTheOpponentLooksAtAreHiddenButAPublicChoiceIsNot() {
        // "mira las primeras 2": they leave the deck and are only in the choice
        CardSlot looked = host.getDeck().remove(0);
        CardSlot tribute = card("tr", "En Tributos", 1, 1, "R");
        tribute.setFaceDown(false);
        host.getTributeZone().add(tribute);
        Prompt prompt = new Prompt();
        prompt.setId("p2");
        prompt.setPlayerId("host");
        prompt.setCards(List.of(looked, tribute));
        room.setPrompt(prompt);
        JsonNode guestSees = seenBy("guest").path("prompt").path("cards");
        assertThat(inZone(guestSees, looked.getInstanceId()).path("cardName").isNull()).isTrue();
        assertThat(inZone(guestSees, "tr").path("cardName").asString()).isEqualTo("En Tributos");
        assertThat(inZone(seenBy("host").path("prompt").path("cards"), looked.getInstanceId()).path("cardName").asString()).isEqualTo("Carta 1");
    }

    @Test
    void theOpponentsActionsAreNotSent() {
        room.setCurrentTurnUserId("guest");
        JsonNode legal = seenBy("host").path("legalActions");
        assertThat(legal.has("guest")).isFalse();
    }

    @Test
    void aDrawnCardIsNotNamedToTheOpponent() {
        CardSlot drawn = host.getDeck().get(0);
        EffectEngine.record(room, GameEvent.DRAW, "host", drawn, "deck", "hand", null, null, null);
        host.getDeck().remove(drawn);
        host.getHand().add(drawn);
        JsonNode guestSees = seenBy("guest").path("timeline");
        JsonNode hostSees = seenBy("host").path("timeline");
        assertThat(guestSees.get(guestSees.size() - 1).path("cardName").isNull()).isTrue();
        assertThat(hostSees.get(hostSees.size() - 1).path("cardName").asString()).isEqualTo("Carta 1");
    }

    @Test
    void aTestRoomIsSentWhole() {
        room.setEditor(true);
        assertThat(StateMask.forViewer(room, GameStateUpdate.from(room, "TEST", "host"), "host")).isInstanceOf(GameStateUpdate.class);
    }

    @Test
    void everyPlayerGetsTheUpdateButNotTheTestRoomsRival() {
        GameStateUpdate update = GameStateUpdate.from(room, "TEST", "host");
        assertThat(StateMask.recipients(room, update, null, "editor-rival")).containsExactlyInAnyOrder("host", "guest");
        assertThat(StateMask.recipients(null, GameStateUpdate.error("gone", "La sala ya no existe"), "host", "editor-rival")).containsExactly("host");
    }
}
