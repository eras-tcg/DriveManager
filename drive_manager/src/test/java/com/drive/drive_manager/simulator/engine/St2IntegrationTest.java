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

/** ST2 (Dragones) with its real metadata: Evolución, inherited effects, cards tributed from the deck. */
class St2IntegrationTest {

    private static final double FRONT = 20.0;

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
        for (String file : List.of("/simulator/st2-cards.json", "/simulator/st3-cards.json")) {
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
        for (int i = 0; i < 10; i++) {
            host.getDeck().add(slot("Flamagón"));
            guest.getDeck().add(slot("Damon"));
        }
        for (int i = 0; i < 3; i++) {
            CardSlot life = slot("Damon");
            life.setStrength(1);
            guest.getLifeStack().add(life);
        }
    }

    @Test
    void evolutionKeepsTheSummonerAsAResourceAndItsInheritedEffectAttacks() {
        CardSlot duregon = onField(host, slot("Duregón"), FRONT);
        CardSlot galveta = slot("Galveta");
        host.getHand().add(galveta);
        assertThat(EffectEngine.specialByPlayer(room, "host", galveta.getInstanceId(), duregon.getInstanceId())).isNull();
        // Galveta's "En Invocación": put up to 1 card from the hand on top (none), then tribute 2 from the deck top
        EffectEngine.answer(room, "host", room.getPrompt().getId(), List.of(), List.of());
        assertThat(galveta.getResources()).containsExactly(duregon);
        assertThat(host.getTributeZone()).hasSize(2);

        // Attacking: Galveta's own effects plus the one inherited from Duregón (draw 1, tribute 1)
        EffectEngine.attackByPlayer(room, "host", galveta.getInstanceId(), null);
        assertThat(room.getTimeline()).filteredOn(e -> GameEvent.EFFECT_ACTIVATED.equals(e.getType()))
                .extracting(e -> String.valueOf(e.getData().get("label")))
                .contains("Al Atacar");
        assertThat(room.getChain().stream().map(PendingEffect::getScriptId)).contains("duregon:hereda-al-atacar");
    }

    @Test
    void duregonReducesTheEvolutionCostWithFiveDragonsInTheTributos() {
        CardSlot duregon = onField(host, slot("Duregón"), FRONT);
        for (int i = 0; i < 5; i++) host.getTributeZone().add(slot("Flamagón"));
        CardSlot fadrake = slot("Dragón Delta");
        host.getHand().add(fadrake);
        int gems = EffectEngine.gemsOf(room, "host");
        EffectEngine.specialByPlayer(room, "host", fadrake.getInstanceId(), duregon.getInstanceId());
        // Special cost 1, minus 1
        assertThat(EffectEngine.gemsOf(room, "host")).isEqualTo(gems);
    }

    @Test
    void fadrakeDestroysAnEnemyOfStrength5OrLessWhenItAttacks() {
        CardSlot fadrake = onField(host, slot("Fadrake"), FRONT);
        CardSlot weak = onField(guest, slot("Damon"), FRONT);
        onField(guest, slot("Zhaglizer"), FRONT);
        EffectEngine.attackByPlayer(room, "host", fadrake.getInstanceId(), null);
        Prompt target = room.getPrompt();
        assertThat(target.getValidIds()).containsExactly(weak.getInstanceId());
        EffectEngine.answer(room, "host", target.getId(), List.of(weak.getInstanceId()), List.of());
        assertThat(guest.getDiscardPile()).contains(weak);
    }

    @Test
    void dirumyTributedFromTheDeckByAGreenCardDraws() {
        CardSlot wyfreik = onField(host, slot("Wyfreik"), FRONT);
        host.getDeck().add(0, slot("Dírumy"));
        int hand = host.getHand().size();
        // Wyfreik attacks: "tributa 3 cartas del tope de tu mazo" — Dírumy is one of them
        EffectEngine.attackByPlayer(room, "host", wyfreik.getInstanceId(), null);
        assertThat(room.getTimeline()).extracting(GameEvent::getType).contains(GameEvent.TRIBUTE);
        Prompt tribute = room.getPrompt();
        assertThat(tribute.getTitle()).contains("Dírumy");
        assertThat(host.getHand()).hasSize(hand + 1);
    }

    @Test
    void llamaradaTributedByADragonCanBePlayedForLess() {
        CardSlot wyfreik = onField(host, slot("Wyfreik"), FRONT);
        CardSlot llamarada = slot("Llamarada");
        host.getDeck().add(0, llamarada);
        onField(guest, slot("Zhaglizer"), FRONT);
        EffectEngine.attackByPlayer(room, "host", wyfreik.getInstanceId(), null);
        Prompt offer = room.getPrompt();
        assertThat(offer.getPurpose()).isEqualTo("OPTIONAL");
        int gems = EffectEngine.gemsOf(room, "host");
        EffectEngine.answer(room, "host", offer.getId(), List.of(llamarada.getInstanceId()), List.of());
        // Cost 6 minus Wyfreik's level 2
        assertThat(EffectEngine.gemsOf(room, "host")).isEqualTo(gems - 4);
        assertThat(room.getTimeline()).extracting(GameEvent::getType).contains(GameEvent.PLAY);
    }

    // ------------------------------------------------------------------ Helpers

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
