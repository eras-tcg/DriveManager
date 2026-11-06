package com.drive.drive_manager.simulator.room;

import java.util.List;
import java.util.stream.Stream;

/**
 * Finds a card by its instance id. Each caller says which zones are searched, in which order, and whether the cards
 * attached to a card (its materials, then its resources) are searched right after it.
 */
public final class CardLocator {

    private CardLocator() {}

    /** Every zone, in the engine's order: field, hand, tributes, discard pile, deck, lives. */
    public static final List<Zone> ENGINE_ORDER = List.of(Zone.values());
    /** Every zone, in the board's manual actions' order: hand, deck, field, lives, tributes, discard pile. */
    public static final List<Zone> MANUAL_ORDER = List.of(Zone.HAND, Zone.DECK, Zone.FIELD, Zone.LIFE_STACK,
            Zone.TRIBUTE_ZONE, Zone.DISCARD_PILE);

    /** A card found and the zone it is in (for an attached card, the zone of the card it is attached to). */
    public record Found(CardSlot card, Zone zone) {}

    /** The first card with that id in the player's {@code zones}, in order; null when none (or no id). */
    public static Found locate(PlayerState player, String instanceId, List<Zone> zones, boolean includeAttached) {
        if (player == null || instanceId == null) return null;
        for (Zone zone : zones) {
            for (CardSlot c : zone.of(player)) {
                if (instanceId.equals(c.getInstanceId())) return new Found(c, zone);
                if (!includeAttached) continue;
                for (CardSlot m : c.getMaterials()) if (instanceId.equals(m.getInstanceId())) return new Found(m, zone);
                for (CardSlot r : c.getResources()) if (instanceId.equals(r.getInstanceId())) return new Found(r, zone);
            }
        }
        return null;
    }

    /** The card of {@link #locate}, or null. */
    public static CardSlot find(PlayerState player, String instanceId, List<Zone> zones, boolean includeAttached) {
        Found found = locate(player, instanceId, zones, includeAttached);
        return found == null ? null : found.card();
    }

    /** The first card with that id among the {@code players}' zones (player by player, in order); null when none. */
    public static CardSlot find(List<PlayerState> players, String instanceId, List<Zone> zones, boolean includeAttached) {
        for (PlayerState p : players) {
            CardSlot card = find(p, instanceId, zones, includeAttached);
            if (card != null) return card;
        }
        return null;
    }

    /** Every card of the room's players in {@code zones}, each followed by its attached cards when asked. */
    public static Stream<CardSlot> cards(GameRoom room, List<Zone> zones, boolean includeAttached) {
        return room.getPlayers().values().stream()
                .flatMap(p -> zones.stream().flatMap(z -> z.of(p).stream()))
                .flatMap(c -> includeAttached ? Stream.concat(Stream.of(c), Stream.concat(c.getMaterials().stream(), c.getResources().stream()))
                        : Stream.of(c));
    }

    /** The first card with that id anywhere in the room ({@link #cards} order); null when none (or no id). */
    public static CardSlot findInRoom(GameRoom room, String instanceId, List<Zone> zones, boolean includeAttached) {
        if (instanceId == null) return null;
        return cards(room, zones, includeAttached).filter(c -> instanceId.equals(c.getInstanceId())).findFirst().orElse(null);
    }
}
