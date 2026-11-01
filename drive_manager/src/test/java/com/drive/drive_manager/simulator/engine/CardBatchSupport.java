package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A table for the card batches of E1.2, E1.3 and E2: the host with 8 gems and its turn, both players with 8 cards in the deck and 3 lives, and
 * the moves the tests repeat (summon, accept an offered effect, answer a choice).
 */
abstract class CardBatchSupport {

    protected GameRoom room;
    protected PlayerState host;
    protected PlayerState guest;

    @BeforeEach
    void setUpTable() {
        TestTable table = TestTable.inProgress(8, 8);
        room = table.room();
        host = table.host();
        guest = table.guest();
        for (int i = 0; i < 3; i++) {
            host.getLifeStack().add(card("hl" + i, "Vida H" + i, 1, 1, "R"));
            guest.getLifeStack().add(card("gl" + i, "Vida G" + i, 1, 1, "R"));
        }
    }

    /** A creature with classes. */
    protected static CardSlot withClasses(CardSlot card, String... classes) {
        card.setClasses(new ArrayList<>(List.of(classes)));
        return card;
    }

    /** A structure. */
    protected static CardSlot structure(String id, String name, int cost, String color) {
        CardSlot c = card(id, name, cost, 1, color);
        c.setCardType("structure");
        c.setStrength(null);
        return c;
    }

    /** A utility of that cost and color. */
    protected static CardSlot utility(String id, String name, int cost, String color) {
        CardSlot c = TestTable.utility(id, name);
        c.setCost(cost);
        c.setColors(new ArrayList<>(List.of(color)));
        return c;
    }

    /** Normal summon from the hand to the Retaguardia. */
    protected void summon(CardSlot card) {
        if (!host.getHand().contains(card)) host.getHand().add(card);
        assertThat(Summons.normalSummon(room, "host", card.getInstanceId(), 50.0, 75.0)).isNull();
    }

    /** Summoned by an effect into the Línea frontal (a test card that summons it). */
    protected void summonByEffectToFront(CardSlot card) {
        CardEffects.register("invocador de prueba", new EffectScript("invocador-prueba:en-invocacion", EffectScript.Moment.ON_SUMMON, true,
                "En Invocación", "Invoca 1 criatura de tu mano en tu línea frontal sin pagar su coste.",
                List.of(new EffectStep.SummonFromZone("hand", CardFilter.creatures().named(card.getCardName()), EffectStep.SummonFromZone.FREE, true))));
        host.getHand().addAll(List.of(card, TestTable.card("iv", "Invocador De Prueba", 0, 1, "R")));
        assertThat(Summons.normalSummon(room, "host", "iv", 50.0, 75.0)).isNull();
        answer(room.getPrompt(), card.getInstanceId());
    }

    /** Plays a utility or structure from the hand. */
    protected void play(CardSlot card) {
        if (!host.getHand().contains(card)) host.getHand().add(card);
        assertThat(Summons.playByPlayer(room, "host", card.getInstanceId(), 50.0, 75.0)).isNull();
    }

    /** Accepts the effect of that card the engine offers (an optional one, or the confirmation of a lone mandatory one). */
    protected void accept(CardSlot source) {
        Prompt offer = room.getPrompt();
        assertThat(offer).as("se ofrece el efecto de " + source.getCardName()).isNotNull();
        assertThat(offer.getValidIds()).as(offer.getInstruction()).contains(source.getInstanceId());
        assertThat(Answers.answer(room, offer.getPlayerId(), offer.getId(), List.of(source.getInstanceId()), List.of())).isNull();
    }

    protected void answer(Prompt prompt, String... ids) {
        assertThat(prompt).isNotNull();
        assertThat(Answers.answer(room, prompt.getPlayerId(), prompt.getId(), List.of(ids), List.of())).isNull();
    }

    protected void answer(String... ids) {
        answer(room.getPrompt(), ids);
    }

    /** Answers "none" to whatever is asked until nothing is (combat steps go on). */
    protected void drain() {
        for (int i = 0; i < 30 && (room.getPrompt() != null || room.getCombat() != null); i++) {
            Prompt p = room.getPrompt();
            if (p == null) Chain.advance(room);
            else Answers.answer(room, p.getPlayerId(), p.getId(), List.of(), List.of());
        }
    }

    /** Answers "none" until the effect of that card is offered. */
    protected void untilOffered(CardSlot source) {
        for (int i = 0; i < 20; i++) {
            Prompt p = room.getPrompt();
            if (p == null) {
                if (room.getCombat() == null) break;
                Chain.advance(room);
                continue;
            }
            if (p.getValidIds().contains(source.getInstanceId())) return;
            Answers.answer(room, p.getPlayerId(), p.getId(), List.of(), List.of());
        }
        throw new AssertionError("No se ofreció " + source.getCardName());
    }

    protected int gems() {
        return EffectEngine.gemsOf(room, "host");
    }

    protected CardSlot inHand(String id, String name, int cost, int level, String color, String... classes) {
        CardSlot c = withClasses(card(id, name, cost, level, color), classes);
        host.getHand().add(c);
        return c;
    }
}
