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

/** ST4 (Caballeros) with its real metadata: Ascenso, Enfrentar, Perforante and its effects. */
class St4IntegrationTest {

    private static final double FRONT = 20.0;
    private static final double BACK = 80.0;

    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final RoomService service = new RoomService();
    private Map<String, Card> cards;
    private GameRoom room;
    private PlayerState host;
    private PlayerState guest;
    private int ids;

    @BeforeEach
    void setUp() throws Exception {
        cards = new HashMap<>();
        for (String file : List.of("/simulator/st4-cards.json", "/simulator/st3-cards.json")) {
            try (InputStream in = getClass().getResourceAsStream(file)) {
                List<Card> list = json.readValue(in, new TypeReference<>() {});
                list.forEach(c -> cards.put(c.getCardName(), c));
            }
        }
        room = service.createRoom();
        host = new PlayerState("host", "Ana");
        guest = new PlayerState("guest", "Beto");
        room.getPlayers().put("host", host);
        room.getPlayers().put("guest", guest);
        room.setStatus(GameRoom.RoomStatus.IN_PROGRESS);
        room.setCurrentTurnUserId("host");
        room.setCurrentPhase("MAIN");
        room.setSharedCounter(-6);
        host.setResourceCount(5);
        guest.setResourceCount(2);
        for (int i = 0; i < 8; i++) {
            host.getDeck().add(slot("Peón De Línea Frontal"));
            guest.getDeck().add(slot("Damon"));
        }
        for (int i = 0; i < 3; i++) {
            CardSlot life = slot("Damon");
            life.setStrength(1);
            guest.getLifeStack().add(life);
        }
    }

    @Test
    void ascensoByTheRulesWithTheRealRequirement() {
        CardSlot ferreo = slot("Caballero Férreo");
        host.getHand().add(ferreo);
        CardSlot peon = onField(host, slot("Peón De Línea Frontal"), FRONT);
        assertThat(EffectEngine.canSpecial(room, "host", ferreo)).isFalse();
        onField(host, slot("Espada De Los Leones De Fuego"), BACK);
        assertThat(EffectEngine.canSpecial(room, "host", ferreo)).isTrue();
        assertThat(act("host", "RITUAL_SUMMON", Map.of("instanceId", ferreo.getInstanceId(), "summonerId", peon.getInstanceId())).getError()).isNull();
        assertThat(host.getField()).contains(ferreo).doesNotContain(peon);
        assertThat(host.getTributeZone()).contains(peon);
    }

    @Test
    void caballeroUmbrioSummonsARedCreatureToTheFrontLineWhenItAttacks() {
        CardSlot umbrio = onField(host, slot("Caballero Umbrío"), FRONT);
        CardSlot espada = slot("Espada De Los Leones De Fuego");
        host.getHand().add(espada);
        EffectEngine.attackByPlayer(room, "host", umbrio.getInstanceId(), null);
        Prompt offer = room.getPrompt();
        assertThat(offer.getPurpose()).isEqualTo("OPTIONAL");
        EffectEngine.answer(room, "host", offer.getId(), List.of(umbrio.getInstanceId()), List.of());
        Prompt pick = room.getPrompt();
        assertThat(pick.getValidIds()).containsExactly(espada.getInstanceId());
        EffectEngine.answer(room, "host", pick.getId(), List.of(espada.getInstanceId()), List.of());
        assertThat(host.getField()).contains(espada);
        assertThat(EffectEngine.inFrontLine(espada)).isTrue();
    }

    @Test
    void caballeroRealGainsStrengthAndPerforanteOnlyIfItSummoned() {
        CardSlot real = onField(host, slot("Caballero Real"), FRONT);
        CardSlot luke = slot("Luke El Filo Rojo");
        host.getHand().add(luke);
        int before = EffectEngine.strength(real);
        EffectEngine.attackByPlayer(room, "host", real.getInstanceId(), null);
        EffectEngine.answer(room, "host", room.getPrompt().getId(), List.of(real.getInstanceId()), List.of());
        EffectEngine.answer(room, "host", room.getPrompt().getId(), List.of(luke.getInstanceId()), List.of());
        assertThat(EffectEngine.strength(real)).isEqualTo(before + 3);
        assertThat(real.getTurnKeywords()).contains("Perforante");
        // Luke was summoned by effect: Enfrentar 3 (chained) and Celeridad offered; no Celeridad
        assertThat(room.getPrompt().getCardLabels()).containsValue("Celeridad");
        EffectEngine.answer(room, "host", room.getPrompt().getId(), List.of(), List.of());
        assertThat(luke.getTurnKeywords()).contains("Enfrentar 3");
    }

    @Test
    void enfrentarAttacksActiveEnemiesOfThatLevel() {
        CardSlot comandante = onField(host, slot("Comandante De Las Espadas Dragón"), FRONT);
        CardSlot level3 = onField(guest, slot("Zhaglizer"), FRONT);
        CardSlot level4 = onField(guest, slot("Berkelus El Cazarrecompensas Del Bajomundo"), FRONT);
        assertThat(EffectEngine.attackByPlayer(room, "host", comandante.getInstanceId(), level4.getInstanceId())).contains("agotadas");
        CardSlot back = onField(guest, slot("Damon"), BACK);
        assertThat(EffectEngine.attackByPlayer(room, "host", comandante.getInstanceId(), back.getInstanceId())).contains("retaguardia");
        assertThat(EffectEngine.attackByPlayer(room, "host", comandante.getInstanceId(), level3.getInstanceId())).isNull();
        assertThat(room.getTimeline()).extracting(GameEvent::getType).contains(GameEvent.ATTACK_DECLARED);
    }

    @Test
    void perforanteGoesOnToDirectDamageWhenTheBlockerIsDestroyed() {
        CardSlot franchute = onField(host, slot("Franchute"), FRONT);
        onField(guest, slot("Damon"), FRONT);
        EffectEngine.attackByPlayer(room, "host", franchute.getInstanceId(), null);
        // The guest blocks with Damon
        Prompt block = room.getPrompt();
        EffectEngine.answer(room, "guest", block.getId(), List.of(block.getValidIds().get(0)), List.of());
        // Damon's "En Destrucción" for the guest: draw 3, tribute 2
        while (room.getPrompt() != null && "guest".equals(room.getPrompt().getPlayerId())) {
            Prompt p = room.getPrompt();
            EffectEngine.answer(room, "guest", p.getId(), p.getValidIds().subList(0, p.getMin()), List.of());
        }
        assertThat(room.getTimeline()).extracting(GameEvent::getType).containsSubsequence(GameEvent.CLASH, GameEvent.LIFE_DAMAGE);
        assertThat(guest.getLifeStack()).hasSize(2);
    }

    @Test
    void reinoMedievalGainsAGemWhenARedAllyIsSummonedByEffect() {
        CardSlot reino = onField(host, slot("Reino Medieval"), BACK);
        CardSlot umbrio = onField(host, slot("Caballero Umbrío"), FRONT);
        host.getHand().add(slot("Espada De Los Leones De Fuego"));
        host.getHand().add(slot("Peón De Línea Frontal"));
        host.getTributeZone().add(slot("Luke El Filo Rojo"));
        int gems = EffectEngine.gemsOf(room, "host");
        EffectEngine.attackByPlayer(room, "host", umbrio.getInstanceId(), null);
        EffectEngine.answer(room, "host", room.getPrompt().getId(), List.of(umbrio.getInstanceId()), List.of());
        Prompt pick = room.getPrompt();
        EffectEngine.answer(room, "host", pick.getId(), List.of(pick.getValidIds().get(0)), List.of());
        // Reino Medieval is offered after the summon
        Prompt offer = room.getPrompt();
        assertThat(offer.getValidIds()).contains(reino.getInstanceId());
        EffectEngine.answer(room, "host", offer.getId(), List.of(reino.getInstanceId()), List.of());
        assertThat(reino.isTapped()).isTrue();
        // +1 gem (the summoned creature cost 1: net 0) and Luke back to the hand
        Prompt take = room.getPrompt();
        EffectEngine.answer(room, "host", take.getId(), List.of(take.getValidIds().get(0)), List.of());
        assertThat(host.getHand()).extracting(CardSlot::getCardName).contains("Luke El Filo Rojo");
        assertThat(EffectEngine.gemsOf(room, "host")).isEqualTo(gems);
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
        assertThat(meta).as(name).isNotNull();
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
