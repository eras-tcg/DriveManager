package com.drive.drive_manager.simulator.room;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * A player's zones, by the id the clients and the card files use ("field", "hand"...). Declared in the order the
 * engine searches them for a card. Each zone has the Spanish words the game texts have always used for it: the
 * engine's prompts ({@link #shortLabel}), the manual actions' log ({@link #logLabel}) and the special summons'
 * requirements ({@link #ownLabel}); they differ, and are kept as they are because the texts are shown and tested.
 */
public enum Zone {
    FIELD("field", PlayerState::getField, "campo", "el campo", "tu campo"),
    HAND("hand", PlayerState::getHand, "mano", "la mano", "tu mano"),
    TRIBUTE_ZONE("tributeZone", PlayerState::getTributeZone, "tributos", "tributo", "tus tributos"),
    DISCARD_PILE("discardPile", PlayerState::getDiscardPile, "descarte", "descarte", "tu descarte"),
    DECK("deck", PlayerState::getDeck, "mazo", "el mazo", "tu mazo"),
    /** The engine's prompts never named it: they show its id. */
    LIFE_STACK("lifeStack", PlayerState::getLifeStack, "lifeStack", "la vida", "tus vidas");

    private final String id;
    private final Function<PlayerState, List<CardSlot>> cards;
    private final String shortLabel;
    private final String logLabel;
    private final String ownLabel;

    Zone(String id, Function<PlayerState, List<CardSlot>> cards, String shortLabel, String logLabel, String ownLabel) {
        this.id = id;
        this.cards = cards;
        this.shortLabel = shortLabel;
        this.logLabel = logLabel;
        this.ownLabel = ownLabel;
    }

    /** The zone's id, as sent to and from the clients. */
    public String id() {
        return id;
    }

    /** The player's live list of cards in this zone. */
    public List<CardSlot> of(PlayerState player) {
        return cards.apply(player);
    }

    /** Whether {@code zoneId} is this zone's id. */
    public boolean is(String zoneId) {
        return id.equals(zoneId);
    }

    /** The engine's word for it ("tributos", "mano"), as in "de tus tributos". */
    public String shortLabel() {
        return shortLabel;
    }

    /** The manual actions' log word for it ("la mano", "tributo"), as in "desde la mano". */
    public String logLabel() {
        return logLabel;
    }

    /** With its owner ("tu mano", "tus tributos"). */
    public String ownLabel() {
        return ownLabel;
    }

    /** The zone with that id, if there is one. */
    public static Optional<Zone> parse(String zoneId) {
        if (zoneId != null) for (Zone z : values()) if (z.id.equals(zoneId)) return Optional.of(z);
        return Optional.empty();
    }

    /** The engine's word for a zone id; an unknown id is shown as it is. */
    public static String shortLabel(String zoneId) {
        return parse(zoneId).map(Zone::shortLabel).orElse(zoneId);
    }

    /** The manual log's word for a zone id; an unknown id is shown as it is, a missing one as "zona desconocida". */
    public static String logLabel(String zoneId) {
        if (zoneId == null) return "zona desconocida";
        return parse(zoneId).map(Zone::logLabel).orElse(zoneId);
    }
}
