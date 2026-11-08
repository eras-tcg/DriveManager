package com.drive.drive_manager.simulator.room

import kotlin.jvm.JvmName
class PlayerState(userId: String?, username: String?) {
    var userId: String? = userId
    var username: String? = username
    /** The player's icon (an image URL from their profile); null for none. */
    var avatar: String? = null
    /** The player's playmat (an image URL from their profile), under their half of the table; null for none. */
    var playmat: String? = null
    @get:JvmName("isConnected")
    var connected: Boolean = false
    /** When they lost their connection (0 while connected): the owner passes on after a while (a reload is not leaving). */
    var disconnectedAt: Long = 0

    var deckId: String? = null
    /** Pre-room: the deck's name, the starter chosen (a card id of the deck, its first Iniciador by default) and ready. */
    var deckName: String? = null
    var starterCardId: String? = null
    @get:JvmName("isReady")
    var ready: Boolean = false
    /**
     * "Efectos opcionales apagados": the engine does not offer this player's optional triggered effects (they lose
     * their moment); "En Juego" effects of the cards they play are still offered. The page remembers it on the device
     * and sets it on every game.
     */
    @get:JvmName("isSkipOptional")
    var skipOptional: Boolean = false
    var resourceCount: Int = 0
    /** How many turns this player has started (their first turn is 1): the bot's edge counts by them. */
    var turnsStarted: Int = 0

    var hand: MutableList<CardSlot> = ArrayList()
    var deck: MutableList<CardSlot> = ArrayList()
    var originalDeck: MutableList<CardSlot> = ArrayList()
    var lifeStack: MutableList<CardSlot> = ArrayList()
    var tributeZone: MutableList<CardSlot> = ArrayList()
    var discardPile: MutableList<CardSlot> = ArrayList()
    var field: MutableList<CardSlot> = ArrayList()

    // --- What Lombok's @Data generated ---

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is PlayerState) return false
        if (!other.canEqual(this)) return false
        if (connected != other.connected) return false
        if (disconnectedAt != other.disconnectedAt) return false
        if (ready != other.ready) return false
        if (skipOptional != other.skipOptional) return false
        if (resourceCount != other.resourceCount) return false
        if (turnsStarted != other.turnsStarted) return false
        if (userId != other.userId) return false
        if (username != other.username) return false
        if (avatar != other.avatar) return false
        if (playmat != other.playmat) return false
        if (deckId != other.deckId) return false
        if (deckName != other.deckName) return false
        if (starterCardId != other.starterCardId) return false
        if (hand != other.hand) return false
        if (deck != other.deck) return false
        if (originalDeck != other.originalDeck) return false
        if (lifeStack != other.lifeStack) return false
        if (tributeZone != other.tributeZone) return false
        if (discardPile != other.discardPile) return false
        if (field != other.field) return false
        return true
    }

    protected fun canEqual(other: Any?): Boolean = other is PlayerState

    override fun hashCode(): Int {
        val PRIME = 59
        var result = 1
        result = result * PRIME + (if (connected) 79 else 97)
        val h_disconnectedAt = disconnectedAt
        result = result * PRIME + (h_disconnectedAt ushr 32 xor h_disconnectedAt).toInt()
        result = result * PRIME + (if (ready) 79 else 97)
        result = result * PRIME + (if (skipOptional) 79 else 97)
        result = result * PRIME + resourceCount
        result = result * PRIME + turnsStarted
        val h_userId: Any? = userId
        result = result * PRIME + (h_userId?.hashCode() ?: 43)
        val h_username: Any? = username
        result = result * PRIME + (h_username?.hashCode() ?: 43)
        val h_avatar: Any? = avatar
        result = result * PRIME + (h_avatar?.hashCode() ?: 43)
        val h_playmat: Any? = playmat
        result = result * PRIME + (h_playmat?.hashCode() ?: 43)
        val h_deckId: Any? = deckId
        result = result * PRIME + (h_deckId?.hashCode() ?: 43)
        val h_deckName: Any? = deckName
        result = result * PRIME + (h_deckName?.hashCode() ?: 43)
        val h_starterCardId: Any? = starterCardId
        result = result * PRIME + (h_starterCardId?.hashCode() ?: 43)
        val h_hand: Any? = hand
        result = result * PRIME + (h_hand?.hashCode() ?: 43)
        val h_deck: Any? = deck
        result = result * PRIME + (h_deck?.hashCode() ?: 43)
        val h_originalDeck: Any? = originalDeck
        result = result * PRIME + (h_originalDeck?.hashCode() ?: 43)
        val h_lifeStack: Any? = lifeStack
        result = result * PRIME + (h_lifeStack?.hashCode() ?: 43)
        val h_tributeZone: Any? = tributeZone
        result = result * PRIME + (h_tributeZone?.hashCode() ?: 43)
        val h_discardPile: Any? = discardPile
        result = result * PRIME + (h_discardPile?.hashCode() ?: 43)
        val h_field: Any? = field
        result = result * PRIME + (h_field?.hashCode() ?: 43)
        return result
    }

    override fun toString(): String =
        "PlayerState(userId=${userId}, username=${username}, avatar=${avatar}, playmat=${playmat}, connected=${connected}, disconnectedAt=${disconnectedAt}, deckId=${deckId}, deckName=${deckName}, starterCardId=${starterCardId}, ready=${ready}, skipOptional=${skipOptional}, resourceCount=${resourceCount}, turnsStarted=${turnsStarted}, hand=${hand}, deck=${deck}, originalDeck=${originalDeck}, lifeStack=${lifeStack}, tributeZone=${tributeZone}, discardPile=${discardPile}, field=${field})"
}
