package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EffectEngineTest {

    private GameRoom room;
    private PlayerState host;
    private PlayerState guest;
    private CardSlot carnhaus;

    @BeforeEach
    void setUp() {
        room = new GameRoom();
        room.setRoomId("r1");
        host = new PlayerState("host", "Ana");
        guest = new PlayerState("guest", "Beto");
        room.getPlayers().put("host", host);
        room.getPlayers().put("guest", guest);
        room.setStatus(GameRoom.RoomStatus.IN_PROGRESS);
        room.setCurrentTurnUserId("host");
        room.setCurrentPhase("MAIN");
        // The first player counts the Contador negatively: -3 = 3 gems for the host
        room.setSharedCounter(-3);
        host.setResourceCount(3);

        carnhaus = card("carnhaus", "Carnhaus", "creature", 1, "P");
        host.getHand().add(carnhaus);
        host.getHand().add(card("h2", "Damon", "creature", 1, "P"));
        // Deck top first: Roja, Violeta, Blanca, Verde | Azul, Roja
        host.setDeck(new ArrayList<>(List.of(
                card("d1", "Roja", "creature", 1, "R"),
                card("d2", "Violeta", "creature", 1, "P"),
                card("d3", "Blanca", "utility", 1, "W"),
                card("d4", "Verde", "creature", 1, "G"),
                card("d5", "Azul", "creature", 1, "B"),
                card("d6", "Roja 2", "creature", 1, "R"))));
    }

    @Test
    void normalSummonPaysGemsEntersRetaguardiaAndTriggersCarnhaus() {
        assertThat(EffectEngine.normalSummon(room, "host", "carnhaus", 40.0, 20.0)).isNull();

        assertThat(EffectEngine.gemsOf(room, "host")).isEqualTo(2);
        assertThat(host.getField()).extracting(CardSlot::getInstanceId).containsExactly("carnhaus");
        assertThat(carnhaus.getY()).isGreaterThanOrEqualTo(55.0);

        // Timeline: summon, activation, "mira las primeras 4"
        assertThat(room.getTimeline()).extracting(GameEvent::getType)
                .containsExactly(GameEvent.NORMAL_SUMMON, GameEvent.EFFECT_ACTIVATED, GameEvent.LOOK_TOP);
        assertThat(room.getChain()).hasSize(1);

        Prompt prompt = room.getPrompt();
        assertThat(prompt.getKind()).isEqualTo(Prompt.Kind.TAKE_REVEALED_REST_BOTTOM);
        assertThat(prompt.getPlayerId()).isEqualTo("host");
        assertThat(prompt.getCards()).extracting(CardSlot::getInstanceId).containsExactly("d1", "d2", "d3", "d4");
        assertThat(prompt.getValidIds()).containsExactly("d2", "d3");
        assertThat(prompt.getMax()).isEqualTo(1);
        assertThat(host.getDeck()).extracting(CardSlot::getInstanceId).containsExactly("d5", "d6");
    }

    @Test
    void carnhausResolvesTakeRestToBottomThenTributeFromHand() {
        EffectEngine.normalSummon(room, "host", "carnhaus", 40.0, 70.0);
        String takeId = room.getPrompt().getId();

        // Only Violeta or Blanca, and at most 1
        assertThat(EffectEngine.answer(room, "host", takeId, List.of("d1"), List.of())).isEqualTo("Esa carta no cumple lo que pide el efecto");
        assertThat(EffectEngine.answer(room, "host", takeId, List.of("d2", "d3"), List.of())).contains("hasta 1");
        // The opponent cannot answer
        assertThat(EffectEngine.answer(room, "guest", takeId, List.of("d3"), List.of())).contains("otro jugador");

        // Take Blanca; the rest go to the bottom in the order given: Verde, Roja, Violeta
        assertThat(EffectEngine.answer(room, "host", takeId, List.of("d3"), List.of("d4", "d1", "d2"))).isNull();
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).containsExactly("h2", "d3");
        assertThat(host.getDeck()).extracting(CardSlot::getInstanceId).containsExactly("d5", "d6", "d4", "d1", "d2");

        // "Luego: tributa 1 carta de tu mano"
        Prompt tribute = room.getPrompt();
        assertThat(tribute.getKind()).isEqualTo(Prompt.Kind.PICK_FROM_HAND);
        assertThat(tribute.getMin()).isEqualTo(1);
        assertThat(tribute.getMax()).isEqualTo(1);
        assertThat(EffectEngine.answer(room, "host", tribute.getId(), List.of(), List.of())).contains("Debes elegir 1");
        assertThat(EffectEngine.answer(room, "host", tribute.getId(), List.of("h2"), List.of())).isNull();

        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).containsExactly("h2");
        assertThat(room.getPrompt()).isNull();
        assertThat(room.getChain()).isEmpty();
        assertThat(room.isEngineBusy()).isFalse();

        // Every effect action is tagged with the effect and its resolution
        List<GameEvent> timeline = room.getTimeline();
        assertThat(timeline).extracting(GameEvent::getType).containsExactly(
                GameEvent.NORMAL_SUMMON, GameEvent.EFFECT_ACTIVATED, GameEvent.LOOK_TOP,
                GameEvent.ADD_TO_HAND, GameEvent.TRIBUTE, GameEvent.EFFECT_RESOLVED);
        GameEvent added = timeline.get(3);
        GameEvent tributed = timeline.get(4);
        assertThat(added.getCardInstanceId()).isEqualTo("d3");
        assertThat(added.getEffectId()).isNotNull().isEqualTo(tributed.getEffectId());
        assertThat(added.getResolutionId()).isNotNull().isEqualTo(tributed.getResolutionId());
        assertThat(timeline).extracting(GameEvent::getSeq).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        // The deck-bottom move is not a timeline event, but it is in the text log
        assertThat(room.getEventLog()).anyMatch(l -> l.getText().contains("al fondo de su mazo"));
    }

    @Test
    void noValidCardStillAsksToOrderTheRest() {
        host.setDeck(new ArrayList<>(List.of(card("x1", "Roja", "creature", 1, "R"), card("x2", "Verde", "creature", 1, "G"))));
        EffectEngine.normalSummon(room, "host", "carnhaus", 50.0, 70.0);

        Prompt prompt = room.getPrompt();
        assertThat(prompt.getCards()).hasSize(2);
        assertThat(prompt.getValidIds()).isEmpty();
        assertThat(prompt.getMax()).isZero();
        assertThat(EffectEngine.answer(room, "host", prompt.getId(), List.of(), List.of("x2", "x1"))).isNull();
        assertThat(host.getDeck()).extracting(CardSlot::getInstanceId).containsExactly("x2", "x1");
        assertThat(room.getPrompt().getKind()).isEqualTo(Prompt.Kind.PICK_FROM_HAND);
    }

    @Test
    void normalSummonFollowsTheRules() {
        assertThat(EffectEngine.normalSummon(room, "guest", "carnhaus", 50.0, 70.0)).contains("tu turno");

        room.setCurrentPhase("END_PHASE");
        assertThat(EffectEngine.normalSummon(room, "host", "carnhaus", 50.0, 70.0)).contains("fase principal");
        room.setCurrentPhase("MAIN");

        // 0 gems and the opponent's Marcador at 0: cannot pay 1
        room.setSharedCounter(0);
        guest.setResourceCount(0);
        assertThat(EffectEngine.normalSummon(room, "host", "carnhaus", 50.0, 70.0)).contains("No tienes gemas suficientes");
        // Rule 5.0.0.1: may go negative as long as the opponent does not exceed their Marcador
        guest.setResourceCount(1);
        assertThat(EffectEngine.normalSummon(room, "host", "carnhaus", 50.0, 70.0)).isNull();
        assertThat(EffectEngine.gemsOf(room, "guest")).isEqualTo(1);

        // While Carnhaus resolves the game is closed: no other summon
        host.getHand().add(card("other", "Otra", "creature", 0, "P"));
        assertThat(EffectEngine.normalSummon(room, "host", "other", 50.0, 70.0)).contains("resolviéndose");
    }

    @Test
    void onlyCreaturesWithACostAreNormallySummoned() {
        host.getHand().add(card("u1", "Utilidad", "utility", 1, "P"));
        CardSlot noCost = card("n1", "Sin coste", "creature", null, "P");
        host.getHand().add(noCost);
        assertThat(EffectEngine.normalSummon(room, "host", "u1", 50.0, 70.0)).contains("Solo las criaturas");
        assertThat(EffectEngine.normalSummon(room, "host", "n1", 50.0, 70.0)).contains("no tiene coste normal");
    }

    private static CardSlot card(String id, String name, String type, Integer cost, String color) {
        CardSlot c = new CardSlot();
        c.setInstanceId(id);
        c.setCardId("file-" + id);
        c.setCardName(name);
        c.setCardType(type);
        c.setCost(cost);
        c.setLevel(1);
        c.setColors(new ArrayList<>(List.of(color)));
        c.setFaceDown(true);
        return c;
    }
}
