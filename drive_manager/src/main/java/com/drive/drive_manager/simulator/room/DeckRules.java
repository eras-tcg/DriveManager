package com.drive.drive_manager.simulator.room;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What makes a deck legal to play outside the Sala de pruebas, on the server's side: the deck builder's construction
 * rules (eras-web {@code deck-rules.ts}: at most {@link #MAX_CARDS} cards and {@link #MAX_COPIES} copies of a name,
 * counting every printing of it) and what a room demands of a deck ({@link DeckChoice#setDeck}: some card, every card
 * run by the engine; {@code PreRoomActions.handleSetReady}: an Iniciador to start with). Whether the player owns the
 * copies is the collection's (the room's {@code Shelf.refusal}), not this. Pure.
 */
public final class DeckRules {

    /** Cards a deck may have (the deck builder's MAX_CARDS). */
    public static final int MAX_CARDS = 100;
    /** Copies of one card name a deck may have, every printing of it counted (the deck builder's MAX_COPIES). */
    public static final int MAX_COPIES = 4;

    private DeckRules() {}

    /** Why the deck ({@code name}, its cards filled from their metadata) may not be played, or null when it is legal. */
    public static String refusal(String name, List<CardSlot> slots) {
        String deck = "El mazo " + (name == null || name.isBlank() ? "" : name + " ");
        if (slots.isEmpty()) return deck + "no tiene cartas";
        if (slots.size() > MAX_CARDS) return deck + "tiene más de " + MAX_CARDS + " cartas";
        Map<String, Integer> copies = new LinkedHashMap<>();
        for (CardSlot s : slots) {
            String key = s.getCardName() == null ? "" : s.getCardName().toLowerCase(Locale.ROOT);
            if (copies.merge(key, 1, Integer::sum) > MAX_COPIES)
                return deck + "tiene más de " + MAX_COPIES + " copias de " + s.getCardName();
        }
        String unprogrammed = DeckChoice.unprogrammedIn(slots);
        if (unprogrammed != null) return deck + "tiene cartas que todavía no están programadas en el simulador: " + unprogrammed;
        if (slots.stream().noneMatch(CardSlot::isStarter)) return deck + "no tiene un Iniciador";
        return null;
    }
}
