package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Connectors ("y", "Si lo haces") and how effects are chained. */
class ConnectorsAndChainingTest {

    private GameRoom room;
    private PlayerState host;
    private PlayerState guest;

    @BeforeEach
    void setUp() {
        TestTable table = TestTable.inProgress(5, 6);
        room = table.room();
        host = table.host();
        guest = table.guest();
    }

    @Test
    void volcanGainsTheGemButDoesNotTributeWhenItCouldNotDraw() {
        host.setDeck(new ArrayList<>());
        CardSlot volcan = onField(host, card("vol", "Volcán De Dragones", 1, 2, "G"));
        volcan.setCardType("structure");
        volcan.setY(70.0);
        host.getHand().add(card("h1", "Mano", 1, 1, "G"));
        CardSlot dragon = card("drg", "Dragón", 1, 1, "G");
        dragon.setClasses(new ArrayList<>(List.of("dragón")));
        onField(host, dragon);
        EffectEngine.record(room, GameEvent.SPECIAL_SUMMON, "host", dragon, "hand", "field", null, null, null);
        int gems = EffectEngine.gemsOf(room, "host");

        Chain.advance(room);
        Prompt offer = room.getPrompt();
        assertThat(offer).isNotNull();
        Answers.answer(room, "host", offer.getId(), List.of("vol"), List.of());

        assertThat(room.getPrompt()).isNull();
        assertThat(EffectEngine.gemsOf(room, "host")).isEqualTo(gems + 1);
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).containsExactly("h1");
        assertThat(volcan.isTapped()).isTrue();
    }

    @Test
    void aLoneMandatoryEffectIsConfirmedByItsControllerWhenTheRoomAsks() {
        room.setConfirmMandatory(true);
        CardSlot duregon = card("dur", "Duregón", 1, 1, "G");
        host.getHand().add(duregon);
        assertThat(Summons.normalSummon(room, "host", "dur", 50.0, 70.0)).isNull();

        Prompt confirm = room.getPrompt();
        assertThat(confirm.getPurpose()).isEqualTo("ORDER");
        assertThat(confirm.getValidIds()).containsExactly("dur");
        assertThat(room.getChain()).isEmpty();
        Answers.answer(room, "host", confirm.getId(), List.of("dur"), List.of());
        // Chained and resolved: "tributa 3 cartas del tope de tu mazo", then the choice of the card to take
        assertThat(host.getTributeZone()).hasSize(3);
        // The tributed cards are revealed: the history names them
        assertThat(room.getEventLog()).anySatisfy(l -> assertThat(l.getText()).endsWith("(Duregón): Carta 1, Carta 2 y Carta 3"));
    }
}
