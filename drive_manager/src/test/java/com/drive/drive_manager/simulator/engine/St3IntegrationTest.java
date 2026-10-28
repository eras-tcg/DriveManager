package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.simulator.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ST3 cards with their real metadata, played through the room's actions (as the board sends them) and
 * serialized as the clients receive them.
 */
class St3IntegrationTest {

    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final RoomService service = new RoomService();
    private Map<String, Card> cards;
    private GameRoom room;
    private PlayerState host;
    private PlayerState guest;
    private int ids;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/simulator/st3-cards.json")) {
            List<Card> list = json.readValue(in, new TypeReference<>() {});
            cards = new HashMap<>();
            list.forEach(c -> cards.put(c.getCardName(), c));
        }
        room = service.createRoom();
        host = new PlayerState("host", "Ana");
        guest = new PlayerState("guest", "Beto");
        room.getPlayers().put("host", host);
        room.getPlayers().put("guest", guest);
        room.setStatus(GameRoom.RoomStatus.IN_PROGRESS);
        room.setCurrentTurnUserId("host");
        room.setCurrentPhase("MAIN");
        room.setSharedCounter(-5);
        host.setResourceCount(5);
        guest.setResourceCount(2);
        for (int i = 0; i < 8; i++) {
            host.getDeck().add(slot("Damon"));
            guest.getDeck().add(slot("Damon"));
        }
        for (int i = 0; i < 3; i++) guest.getLifeStack().add(slot("Buratán"));
    }

    @Test
    void everyCardIsMappedFromItsMetadata() {
        for (String name : cards.keySet()) {
            CardSlot s = slot(name);
            if (name.equals("Buratán")) {
                assertThat(CardEffects.of(s)).isEmpty();
                assertThat(s.getKeywords()).containsExactly("Provocar");
            } else {
                assertThat(CardEffects.of(s)).as(name).isNotEmpty();
            }
        }
        assertThat(slot("Hong").getKeywords()).containsExactly("Letalidad 1");
        assertThat(slot("Bajmut Pútrido").getKeywords()).containsExactly("Imbloqueable");
        assertThat(CardEffects.of(slot("Cazador De Bestias"))).extracting(EffectScript::id).contains("kw:celeridad");
        CardSlot mawser = slot("Mawser");
        assertThat(mawser.getSpecialCost()).isEqualTo(3);
        assertThat(mawser.getRequirement()).contains("sumen 6");
        assertThat(slot("Damon").getClasses()).contains("bestia");
        assertThat(slot("Cueva De Bestias").getCardType()).isEqualTo("structure");
    }

    @Test
    void zhaglizerAttacksThroughTheRoomActions() throws Exception {
        CardSlot zhaglizer = onField(host, slot("Zhaglizer"), 20.0);
        CardSlot damon = onField(guest, slot("Damon"), 20.0);

        GameStateUpdate attack = act("host", "ATTACK", Map.of("instanceId", zhaglizer.getInstanceId()));
        assertThat(attack.getError()).isNull();
        assertThat(attack.getCombat()).isNotNull();
        Prompt destroy = attack.getPrompt();
        assertThat(destroy.getValidIds()).containsExactly(damon.getInstanceId());
        // Clients receive it as JSON
        String sent = json.writeValueAsString(attack);
        assertThat(sent).contains("\"combat\"").contains("\"chosenLabel\":\"Destruir\"");

        GameStateUpdate destroyed = act("host", "RESOLVE_PROMPT", Map.of("promptId", destroy.getId(), "chosen", List.of(damon.getInstanceId())));
        assertThat(destroyed.getError()).isNull();
        // Damon's "En Destrucción": the guest draws 3 and tributes 2
        Prompt tribute = destroyed.getPrompt();
        assertThat(tribute.getPlayerId()).isEqualTo("guest");
        assertThat(act("host", "RESOLVE_PROMPT", Map.of("promptId", tribute.getId(), "chosen", List.of())).getError())
                .contains("otro jugador");
        List<String> two = guest.getHand().stream().limit(2).map(CardSlot::getInstanceId).toList();
        GameStateUpdate after = act("guest", "RESOLVE_PROMPT", Map.of("promptId", tribute.getId(), "chosen", two));
        assertThat(after.getError()).isNull();

        // No blockers left: direct damage, a Buratán life (strength 4 ≤ 8) is tributed
        assertThat(after.getCombat()).isNull();
        assertThat(guest.getLifeStack()).hasSize(2);
        assertThat(guest.getTributeZone()).hasSize(3);
        assertThat(after.getTimeline()).extracting(GameEvent::getType).containsSubsequence(
                GameEvent.ATTACK_DECLARED, GameEvent.EFFECT_ACTIVATED, GameEvent.DESTROY, GameEvent.EFFECT_RESOLVED,
                GameEvent.EFFECT_ACTIVATED, GameEvent.DRAW, GameEvent.TRIBUTE, GameEvent.EFFECT_RESOLVED,
                GameEvent.LIFE_DAMAGE, GameEvent.ATTACK_ENDED);
    }

    @Test
    void manualActionsWaitWhileTheCombatIsOpen() {
        CardSlot zhaglizer = onField(host, slot("Zhaglizer"), 20.0);
        onField(guest, slot("Damon"), 20.0);
        act("host", "ATTACK", Map.of("instanceId", zhaglizer.getInstanceId()));
        assertThat(act("host", "MOVE_CARD", Map.of("instanceId", zhaglizer.getInstanceId(), "fromZone", "field", "toZone", "hand")).getError())
                .contains("resolviéndose");
    }

    @Test
    void playingCuevaDeBestiasAndSacrificingAVioletaAlly() {
        CardSlot cueva = slot("Cueva De Bestias");
        host.getHand().add(cueva);
        CardSlot carnhaus = onField(host, slot("Carnhaus"), 70.0);
        int gems = EffectEngine.gemsOf(room, "host");

        assertThat(act("host", "PLAY_CARD", Map.of("instanceId", cueva.getInstanceId(), "x", 30, "y", 80)).getError()).isNull();
        assertThat(EffectEngine.gemsOf(room, "host")).isEqualTo(gems - 1);
        assertThat(host.getField()).contains(cueva);

        GameStateUpdate sacrificed = act("host", "SACRIFICE_CARD", Map.of("instanceId", carnhaus.getInstanceId()));
        // Carnhaus' own "En Destrucción" (mandatory) goes first; Cueva (optional) asks
        Prompt confirm = sacrificed.getPrompt();
        assertThat(confirm.getKind()).isEqualTo(Prompt.Kind.CONFIRM);
        assertThat(confirm.getSourceName()).isEqualTo("Cueva De Bestias");
        act("host", "RESOLVE_PROMPT", Map.of("promptId", confirm.getId(), "chosen", List.of(cueva.getInstanceId())));
        assertThat(cueva.isTapped()).isTrue();
        assertThat(EffectEngine.gemsOf(room, "host")).isEqualTo(gems);
    }

    @Test
    void endingTheTurnOffersTheNextPlayerToMoveUp() {
        onField(guest, slot("Damon"), 75.0);
        GameStateUpdate ended = act("host", "END_TURN", Map.of());
        assertThat(ended.getPrompt()).isNotNull();
        assertThat(ended.getPrompt().getPlayerId()).isEqualTo("guest");
        assertThat(ended.getPrompt().getPurpose()).isEqualTo("ADVANCE");
        // Until it is answered the game waits
        assertThat(act("guest", "DRAW_CARD", Map.of()).getError()).isNotNull();
        assertThat(act("guest", "RESOLVE_PROMPT", Map.of("promptId", ended.getPrompt().getId(), "chosen", List.of())).getError()).isNull();
        assertThat(room.isEngineBusy()).isFalse();
    }

    @Test
    void theStarterJoinsTheHandAfterTheMulligan() {
        room.setStatus(GameRoom.RoomStatus.SELECTING_STARTER);
        for (PlayerState p : List.of(host, guest)) {
            p.getDeck().clear();
            p.getLifeStack().clear();
            for (int i = 0; i < 20; i++) p.getDeck().add(slot("Damon"));
            p.getDeck().add(slot("Carnhaus"));
        }
        for (PlayerState p : List.of(host, guest)) {
            String starter = p.getDeck().stream().filter(CardSlot::isStarter).findFirst().orElseThrow().getInstanceId();
            assertThat(act(p.getUserId(), "SELECT_STARTER", Map.of("instanceId", starter)).getError()).isNull();
        }
        for (PlayerState p : List.of(host, guest)) {
            assertThat(act(p.getUserId(), "CONFIRM_MULLIGAN", Map.of()).getError()).isNull();
        }
        for (PlayerState p : List.of(host, guest)) {
            assertThat(p.getHand()).hasSize(6).anyMatch(c -> "Carnhaus".equals(c.getCardName()));
            assertThat(p.getField()).isEmpty();
            assertThat(p.getLifeStack()).hasSize(6);
        }
    }

    @Test
    void aRematchDrawsWhoStartsAgain() {
        room.setStatus(GameRoom.RoomStatus.POST_GAME);
        for (PlayerState p : List.of(host, guest)) assertThat(act(p.getUserId(), "POST_GAME_CHOICE", Map.of("choice", "rematch")).getError()).isNull();
        assertThat(room.getStatus()).isEqualTo(GameRoom.RoomStatus.SELECTING_STARTER);
        assertThat(room.getCurrentTurnUserId()).isIn("host", "guest");
        assertThat(room.getFirstPlayerId()).isEqualTo(room.getCurrentTurnUserId());
        assertThat(room.getStarterAdvantageUserId()).isEqualTo(room.getOpponentId(room.getCurrentTurnUserId()));
    }

    @Test
    void ritualAndSlowEffectsThroughTheRoomActions() throws Exception {
        CardSlot zhaglizer = slot("Zhaglizer");
        host.getHand().add(zhaglizer);
        CardSlot summoner = onField(host, slot("Hong"), 20.0);
        summoner.setLevel(2);
        GameStateUpdate ritual = act("host", "RITUAL_SUMMON", Map.of("instanceId", zhaglizer.getInstanceId(), "summonerId", summoner.getInstanceId()));
        assertThat(ritual.getError()).isNull();
        assertThat(host.getField()).contains(zhaglizer);

        // The slow effects each player can activate travel with the state
        CardSlot aranador = onField(host, slot("Zhaglizer"), 20.0);
        aranador.setCardName("Arañador");
        aranador.setTapped(true);
        GameStateUpdate state = GameStateUpdate.from(room, "TEST", "host");
        assertThat(state.getActivatable()).containsKey("host");
        assertThat(json.writeValueAsString(state)).contains("\"scriptId\":\"aranador:turno-aliado\"");
        assertThat(act("host", "ACTIVATE_EFFECT", Map.of("instanceId", aranador.getInstanceId())).getError()).isNull();
        assertThat(room.getPrompt().getValidIds()).containsExactly(zhaglizer.getInstanceId());
    }

    // ------------------------------------------------------------------ Helpers

    private GameStateUpdate act(String userId, String type, Map<String, Object> payload) {
        GameAction action = new GameAction();
        action.setType(type);
        action.setPayload(new HashMap<>(payload));
        return service.applyAction(room.getRoomId(), userId, action);
    }

    private CardSlot slot(String name) {
        Card meta = cards.get(name);
        CardSlot s = new CardSlot();
        s.setInstanceId("c" + (++ids));
        s.setCardId(meta.getId());
        s.setFaceDown(true);
        ReflectionTestUtils.invokeMethod(service, "applyMeta", s, meta.getId(), meta);
        return s;
    }

    private static CardSlot onField(PlayerState p, CardSlot c, double y) {
        c.setX(50.0);
        c.setY(y);
        c.setFaceDown(false);
        p.getField().add(c);
        return c;
    }
}
