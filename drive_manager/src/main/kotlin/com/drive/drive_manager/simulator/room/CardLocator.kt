package com.drive.drive_manager.simulator.room

import java.util.stream.Stream

/**
 * Finds a card by its instance id. Each caller says which zones are searched, in which order, and whether the cards
 * attached to a card (its materials, then its resources) are searched right after it.
 */
object CardLocator {

    /** Every zone, in the engine's order: field, hand, tributes, discard pile, deck, lives. */
    @JvmField val ENGINE_ORDER: List<Zone> = java.util.List.of(*Zone.values())
    /** Every zone, in the board's manual actions' order: hand, deck, field, lives, tributes, discard pile. */
    @JvmField val MANUAL_ORDER: List<Zone> = java.util.List.of(Zone.HAND, Zone.DECK, Zone.FIELD, Zone.LIFE_STACK,
            Zone.TRIBUTE_ZONE, Zone.DISCARD_PILE)

    /** A card found and the zone it is in (for an attached card, the zone of the card it is attached to). */
    @JvmRecord
    data class Found(val card: CardSlot?, val zone: Zone?)

    /** The first card with that id in the player's `zones`, in order; null when none (or no id). */
    @JvmStatic
    fun locate(player: PlayerState?, instanceId: String?, zones: List<Zone>, includeAttached: Boolean): Found? {
        if (player == null || instanceId == null) return null
        for (zone in zones) {
            for (c in zone.of(player)) {
                if (instanceId == c.instanceId) return Found(c, zone)
                if (!includeAttached) continue
                for (m in c.materials!!) if (instanceId == m.instanceId) return Found(m, zone)
                for (r in c.resources!!) if (instanceId == r.instanceId) return Found(r, zone)
            }
        }
        return null
    }

    /** The card of [locate], or null. */
    @JvmStatic
    fun find(player: PlayerState?, instanceId: String?, zones: List<Zone>, includeAttached: Boolean): CardSlot? {
        val found = locate(player, instanceId, zones, includeAttached)
        return found?.card
    }

    /** The first card with that id among the `players`' zones (player by player, in order); null when none. */
    @JvmStatic
    fun find(players: List<PlayerState?>, instanceId: String?, zones: List<Zone>, includeAttached: Boolean): CardSlot? {
        for (p in players) {
            val card = find(p, instanceId, zones, includeAttached)
            if (card != null) return card
        }
        return null
    }

    /** Every card of the room's players in `zones`, each followed by its attached cards when asked. */
    @JvmStatic
    fun cards(room: GameRoom, zones: List<Zone>, includeAttached: Boolean): Stream<CardSlot> {
        return room.players.values.stream()
            .flatMap { p -> zones.stream().flatMap { z -> z.of(p).stream() } }
            .flatMap { c ->
                if (includeAttached) Stream.concat(Stream.of(c), Stream.concat(c.materials!!.stream(), c.resources!!.stream()))
                else Stream.of(c)
            }
    }

    /** The first card with that id anywhere in the room ([cards] order); null when none (or no id). */
    @JvmStatic
    fun findInRoom(room: GameRoom, instanceId: String?, zones: List<Zone>, includeAttached: Boolean): CardSlot? {
        if (instanceId == null) return null
        return cards(room, zones, includeAttached).filter { instanceId == it.instanceId }.findFirst().orElse(null)
    }
}
