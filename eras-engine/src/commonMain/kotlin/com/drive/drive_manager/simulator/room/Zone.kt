package com.drive.drive_manager.simulator.room

import kotlin.jvm.JvmStatic

/**
 * A player's zones, by the id the clients and the card files use ("field", "hand"...). Declared in the order the
 * engine searches them for a card. Each zone has the Spanish words the game texts have always used for it: the
 * engine's prompts ([shortLabel]), the manual actions' log ([logLabel]) and the special summons'
 * requirements ([ownLabel]); they differ, and are kept as they are because the texts are shown and tested.
 */
enum class Zone(
    private val id: String,
    private val cards: (PlayerState) -> MutableList<CardSlot>,
    private val shortLabel: String,
    private val logLabel: String,
    private val ownLabel: String,
) {
    FIELD("field", PlayerState::field, "campo", "el campo", "tu campo"),
    HAND("hand", PlayerState::hand, "mano", "la mano", "tu mano"),
    TRIBUTE_ZONE("tributeZone", PlayerState::tributeZone, "tributos", "tributo", "tus tributos"),
    DISCARD_PILE("discardPile", PlayerState::discardPile, "descarte", "descarte", "tu descarte"),
    DECK("deck", PlayerState::deck, "mazo", "el mazo", "tu mazo"),
    /** The engine's prompts never named it: they show its id. */
    LIFE_STACK("lifeStack", PlayerState::lifeStack, "lifeStack", "la vida", "tus vidas");

    /** The zone's id, as sent to and from the clients. */
    fun id(): String = id

    /** The player's live list of cards in this zone. */
    fun of(player: PlayerState): MutableList<CardSlot> = cards(player)

    /** Whether `zoneId` is this zone's id. */
    fun `is`(zoneId: String?): Boolean = id == zoneId

    /** The engine's word for it ("tributos", "mano"), as in "de tus tributos". */
    fun shortLabel(): String = shortLabel

    /** The manual actions' log word for it ("la mano", "tributo"), as in "desde la mano". */
    fun logLabel(): String = logLabel

    /** With its owner ("tu mano", "tus tributos"). */
    fun ownLabel(): String = ownLabel

    companion object {
        /** The zone with that id, if there is one. */
        @JvmStatic
        fun parse(zoneId: String?): Zone? {
            if (zoneId != null) for (z in values()) if (z.id == zoneId) return z
            return null
        }

        /** The engine's word for a zone id; an unknown id is shown as it is. */
        @JvmStatic
        fun shortLabel(zoneId: String?): String? = parse(zoneId)?.shortLabel() ?: zoneId

        /** The manual log's word for a zone id; an unknown id is shown as it is, a missing one as "zona desconocida". */
        @JvmStatic
        fun logLabel(zoneId: String?): String? {
            if (zoneId == null) return "zona desconocida"
            return parse(zoneId)?.logLabel() ?: zoneId
        }
    }
}
