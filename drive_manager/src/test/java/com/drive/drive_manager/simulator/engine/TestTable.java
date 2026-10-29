package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;

import java.util.ArrayList;
import java.util.List;

/**
 * A game for the engine's tests: two players ("host", whose turn and main phase it is, and "guest"), the host with
 * {@code gems} gems, both with a deck of plain cards. Plus the cards to put on it.
 */
record TestTable(GameRoom room, PlayerState host, PlayerState guest) {

    /** Line heights of the field (the Línea frontal is the upper half of a side). */
    static final double FRONT = 20.0;
    static final double BACK = 70.0;

    static TestTable inProgress(int gems, int deckSize) {
        GameRoom room = new GameRoom();
        room.setRoomId("r1");
        PlayerState host = new PlayerState("host", "Ana");
        PlayerState guest = new PlayerState("guest", "Beto");
        room.getPlayers().put("host", host);
        room.getPlayers().put("guest", guest);
        room.setStatus(GameRoom.RoomStatus.IN_PROGRESS);
        room.setCurrentTurnUserId("host");
        room.setCurrentPhase("MAIN");
        // The first player counts the shared gems negatively
        room.setSharedCounter(-gems);
        host.setResourceCount(gems);
        host.setDeck(deck("hd", deckSize));
        guest.setDeck(deck("gd", deckSize));
        return new TestTable(room, host, guest);
    }

    /** A creature (strength 3, face down until it is placed). */
    static CardSlot card(String id, String name, Integer cost, int level, String color) {
        CardSlot c = new CardSlot();
        c.setInstanceId(id);
        c.setCardId("file-" + id);
        c.setCardName(name);
        c.setCardType("creature");
        c.setCost(cost);
        c.setLevel(level);
        c.setStrength(3);
        c.setColors(new ArrayList<>(List.of(color)));
        c.setFaceDown(true);
        return c;
    }

    /** A utility (no strength). */
    static CardSlot utility(String id, String name) {
        CardSlot c = card(id, name, 0, 1, "W");
        c.setCardType("utility");
        c.setStrength(null);
        return c;
    }

    /** Plain creatures "Carta 1", "Carta 2"… */
    static List<CardSlot> deck(String prefix, int n) {
        List<CardSlot> cards = new ArrayList<>();
        for (int i = 1; i <= n; i++) cards.add(card(prefix + i, "Carta " + i, 1, 1, "R"));
        return cards;
    }

    /** On the field, face up, at that height. */
    static CardSlot onField(PlayerState p, CardSlot c, double y) {
        c.setX(50.0);
        c.setY(y);
        c.setFaceDown(false);
        p.getField().add(c);
        return c;
    }

    /** On the field, in the Línea frontal. */
    static CardSlot onField(PlayerState p, CardSlot c) {
        return onField(p, c, FRONT);
    }
}
