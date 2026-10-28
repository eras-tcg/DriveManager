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

/** ST1 (Entes) with its real metadata: Materialización, materials as costs, strength reduction and its triggers. */
class St1IntegrationTest {

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
        for (String file : List.of("/simulator/st1-cards.json", "/simulator/st3-cards.json")) {
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
            host.getDeck().add(slot("Fhalid"));
            guest.getDeck().add(slot("Damon"));
        }
        for (int i = 0; i < 3; i++) {
            CardSlot life = slot("Damon");
            life.setStrength(1);
            guest.getLifeStack().add(life);
        }
    }

    @Test
    void bertalumMaterializedReducesAnEnemyToZeroAndTheTributosReact() {
        // Bertalum by Materialización: a level 2 summoner and a level 2 material
        CardSlot summoner = onField(host, slot("Fhalid"), FRONT);
        CardSlot material = slot("Zula");
        host.getTributeZone().add(material);
        CardSlot falis = slot("Falis");
        host.getTributeZone().add(falis);
        CardSlot bertalum = slot("Bertalum");
        host.getHand().add(bertalum);
        CardSlot damon = onField(guest, slot("Damon"), FRONT);

        assertThat(EffectEngine.specialByPlayer(room, "host", bertalum.getInstanceId(), summoner.getInstanceId())).isNull();
        EffectEngine.answer(room, "host", room.getPrompt().getId(), List.of(material.getInstanceId()), List.of());
        assertThat(bertalum.getMaterials()).containsExactly(summoner, material);

        // Its "En Invocación": tribute both materials and reduce Damon (3) by 4: destroyed
        Prompt offer = room.getPrompt();
        assertThat(offer.getPurpose()).isEqualTo("OPTIONAL");
        EffectEngine.answer(room, "host", offer.getId(), List.of(bertalum.getInstanceId()), List.of());
        EffectEngine.answer(room, "host", room.getPrompt().getId(), room.getPrompt().getValidIds(), List.of());
        EffectEngine.answer(room, "host", room.getPrompt().getId(), List.of(damon.getInstanceId()), List.of());
        assertThat(guest.getDiscardPile()).contains(damon);
        assertThat(room.getTimeline()).extracting(GameEvent::getType).contains(GameEvent.STRENGTH_REDUCED);

        // Falis, in the Tributos, may now be summoned for 2 less
        boolean offered = false;
        for (int i = 0; i < 6 && room.getPrompt() != null; i++) {
            Prompt p = room.getPrompt();
            if ("host".equals(p.getPlayerId()) && "OPTIONAL".equals(p.getPurpose()) && p.getValidIds().contains(falis.getInstanceId())) {
                offered = true;
                EffectEngine.answer(room, "host", p.getId(), List.of(falis.getInstanceId()), List.of());
                break;
            }
            List<String> pick = p.getMin() > 0 ? p.getValidIds().subList(0, p.getMin()) : List.of();
            EffectEngine.answer(room, p.getPlayerId(), p.getId(), pick, List.of());
        }
        assertThat(offered).isTrue();
        assertThat(host.getField()).contains(falis);
    }

    @Test
    void jhavDrawsWhenItTributesAnEnte() {
        CardSlot jhav = slot("Jhav");
        host.getHand().add(jhav);
        host.getHand().add(slot("Eisten"));
        host.getTributeZone().add(slot("Flarix"));
        assertThat(EffectEngine.normalSummon(room, "host", jhav.getInstanceId(), 50.0, 75.0)).isNull();
        // Take up to 1 Azul (Flarix), tribute 1 (an Ente) and draw 1
        EffectEngine.answer(room, "host", room.getPrompt().getId(), room.getPrompt().getValidIds(), List.of());
        int hand = host.getHand().size();
        Prompt tribute = room.getPrompt();
        EffectEngine.answer(room, "host", tribute.getId(), List.of(tribute.getValidIds().get(0)), List.of());
        assertThat(host.getHand()).hasSize(hand);
    }

    @Test
    void composicionAdimensionalStaysExhaustedOnTheField() {
        CardSlot composicion = slot("Composición Adimensional");
        host.getHand().add(composicion);
        host.getHand().add(slot("Eisten"));
        host.getHand().add(slot("Jhav"));
        assertThat(EffectEngine.playByPlayer(room, "host", composicion.getInstanceId(), 50.0, 75.0)).isNull();
        Prompt tribute = room.getPrompt();
        EffectEngine.answer(room, "host", tribute.getId(), tribute.getValidIds(), List.of());
        assertThat(host.getHand()).hasSize(2);
        assertThat(host.getField()).contains(composicion);
        assertThat(composicion.isTapped()).isTrue();
    }

    @Test
    void mathausActivatesItsSlowEffectWithTwoMaterials() {
        CardSlot mathaus = onField(host, slot("Mathaus"), FRONT);
        mathaus.getMaterials().add(slot("Eisten"));
        mathaus.getMaterials().add(slot("Jhav"));
        CardSlot enemy = onField(guest, slot("Zhaglizer"), FRONT);
        assertThat(EffectEngine.activatable(room, "host")).extracting(EffectEngine.Activatable::scriptId).contains("mathaus:turno-aliado");
        assertThat(EffectEngine.activateByPlayer(room, "host", mathaus.getInstanceId(), null)).isNull();
        EffectEngine.answer(room, "host", room.getPrompt().getId(), room.getPrompt().getValidIds(), List.of());
        EffectEngine.answer(room, "host", room.getPrompt().getId(), List.of(enemy.getInstanceId()), List.of());
        // Until the end of the opponent's turn
        assertThat(enemy.getLaterStrength()).isEqualTo(-3);
        assertThat(mathaus.getMaterials()).isEmpty();
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
