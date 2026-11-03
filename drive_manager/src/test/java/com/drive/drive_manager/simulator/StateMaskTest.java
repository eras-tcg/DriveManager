package com.drive.drive_manager.simulator;

import com.drive.drive_manager.simulator.engine.GameEvent;
import com.drive.drive_manager.simulator.engine.Prompt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Each player is sent only what they may see of the room. */
class StateMaskTest {

    private final RoomService service = new RoomService();
    private GameRoom room;

    @BeforeEach
    void setUp() {
        room = service.createRoom();
        for (String id : List.of("p1", "p2")) {
            service.applyAction(room.getRoomId(), id, action("JOIN_ROOM", Map.of("username", id)));
            PlayerState p = room.getPlayer(id);
            p.getHand().add(card(id + "-hand", false));
            p.getDeck().add(card(id + "-deck", false));
            p.getOriginalDeck().add(card(id + "-deck", false));
            p.getLifeStack().add(card(id + "-life", true));
            p.getField().add(card(id + "-field", false));
            p.getField().add(card(id + "-set", true));
            p.getDiscardPile().add(card(id + "-discard-down", true));
        }
        room.setStatus(GameRoom.RoomStatus.IN_PROGRESS);
    }

    @Test
    void theRivalsHiddenCardsAreBacksAndTheirDeckListStaysOut() {
        GameStateUpdate view = StateMask.forViewer(GameStateUpdate.from(room, "X", "p1"), "p1", false);
        PlayerState rival = view.getPlayers().get("p2");

        assertThat(rival.getHand()).hasSize(1).allSatisfy(StateMaskTest::isBack);
        assertThat(rival.getHand().get(0).getInstanceId()).isEqualTo(room.getPlayer("p2").getHand().get(0).getInstanceId());
        assertThat(rival.getDeck()).hasSize(1).allSatisfy(StateMaskTest::isBack);
        assertThat(rival.getDeck().get(0).getInstanceId()).isNotEqualTo(room.getPlayer("p2").getDeck().get(0).getInstanceId());
        assertThat(rival.getOriginalDeck()).isEmpty();
        // Of the deck list, the pre-room only shows the starter the rival chose
        room.getPlayer("p2").setStarterCardId("p2-deck");
        assertThat(StateMask.forViewer(GameStateUpdate.from(room, "X", "p1"), "p1", false).getPlayers().get("p2").getOriginalDeck())
                .extracting(CardSlot::getCardId).containsExactly("p2-deck");
        assertThat(rival.getLifeStack()).allSatisfy(StateMaskTest::isBack);
        assertThat(rival.getDiscardPile()).allSatisfy(StateMaskTest::isBack);
        assertThat(rival.getField()).extracting(CardSlot::getCardId).containsExactly("p2-field", null);
        // The room itself is untouched
        assertThat(room.getPlayer("p2").getHand().get(0).getCardId()).isEqualTo("p2-hand");
    }

    @Test
    void myHandAndMyFaceDownCardsAreMineButNotMyDeckOrLives() {
        PlayerState me = StateMask.forViewer(GameStateUpdate.from(room, "X", "p1"), "p1", false).getPlayers().get("p1");

        assertThat(me.getHand()).extracting(CardSlot::getCardId).containsExactly("p1-hand");
        assertThat(me.getField()).extracting(CardSlot::getCardId).containsExactly("p1-field", "p1-set");
        assertThat(me.getDiscardPile()).extracting(CardSlot::getCardId).containsExactly("p1-discard-down");
        assertThat(me.getOriginalDeck()).extracting(CardSlot::getCardId).containsExactly("p1-deck");
        assertThat(me.getDeck()).allSatisfy(StateMaskTest::isBack);
        assertThat(me.getLifeStack()).allSatisfy(StateMaskTest::isBack);
    }

    @Test
    void anEditorFixingTheBoardByHandSeesTheirOwnDeckAndLives() {
        PlayerState me = StateMask.forViewer(GameStateUpdate.from(room, "X", "p1"), "p1", true).getPlayers().get("p1");
        assertThat(me.getDeck()).extracting(CardSlot::getCardId).containsExactly("p1-deck");
        assertThat(me.getLifeStack()).extracting(CardSlot::getCardId).containsExactly("p1-life");
        PlayerState rival = StateMask.forViewer(GameStateUpdate.from(room, "X", "p1"), "p1", true).getPlayers().get("p2");
        assertThat(rival.getHand()).allSatisfy(StateMaskTest::isBack);
    }

    @Test
    void theRivalsChoiceShowsWhoChoosesButNotTheCards() {
        Prompt prompt = new Prompt();
        prompt.setId("q1");
        prompt.setPlayerId("p2");
        prompt.setTitle("Elige");
        prompt.getCards().add(room.getPlayer("p2").getDeck().get(0));
        prompt.getValidIds().add(room.getPlayer("p2").getDeck().get(0).getInstanceId());
        room.setPrompt(prompt);

        Prompt seenByP1 = StateMask.forViewer(GameStateUpdate.from(room, "X", "p2"), "p1", false).getPrompt();
        assertThat(seenByP1.getPlayerId()).isEqualTo("p2");
        assertThat(seenByP1.getTitle()).isEqualTo("Elige");
        assertThat(seenByP1.getCards()).isEmpty();
        assertThat(seenByP1.getValidIds()).isEmpty();
        assertThat(StateMask.forViewer(GameStateUpdate.from(room, "X", "p2"), "p2", false).getPrompt().getCards()).hasSize(1);
    }

    @Test
    void theTimelineDoesNotNameWhatTheRivalDrew() {
        GameEvent draw = new GameEvent();
        draw.setType(GameEvent.DRAW);
        draw.setPlayerId("p2");
        draw.setCardName("Secreta");
        room.getTimeline().add(draw);

        assertThat(StateMask.forViewer(GameStateUpdate.from(room, "X", "p2"), "p1", false).getTimeline())
                .extracting(GameEvent::getCardName).containsOnlyNulls();
        assertThat(StateMask.forViewer(GameStateUpdate.from(room, "X", "p2"), "p2", false).getTimeline())
                .extracting(GameEvent::getCardName).containsExactly("Secreta");
    }

    @Test
    void aTestRoomIsSentWhole() {
        room.setEditor(true);
        GameStateUpdate update = GameStateUpdate.from(room, "X", "p1");
        assertThat(StateMask.forViewer(update, "p1", false)).isSameAs(update);
    }

    private static void isBack(CardSlot c) {
        assertThat(c.isFaceDown()).isTrue();
        assertThat(c.getCardId()).isNull();
        assertThat(c.getCardName()).isNull();
        assertThat(c.getImageUrl()).isNull();
    }

    private static CardSlot card(String cardId, boolean faceDown) {
        CardSlot c = new CardSlot();
        c.setInstanceId(UUID.randomUUID().toString());
        c.setCardId(cardId);
        c.setCardName(cardId);
        c.setImageUrl("https://img/" + cardId);
        c.setFaceDown(faceDown);
        return c;
    }

    private static GameAction action(String type, Map<String, Object> payload) {
        GameAction a = new GameAction();
        a.setType(type);
        a.setPayload(new HashMap<>(payload));
        return a;
    }
}
