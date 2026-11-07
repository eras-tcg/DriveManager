package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.room.CardSlot

/**
 * A choice the engine is waiting for. Only [playerId] can answer it (RESOLVE_PROMPT) and it cannot be
 * dismissed: the effect does not go on until it is answered.
 */
class Prompt {

    enum class Kind {
        /** Pick up to `max` of the revealed cards for the hand (only `validIds`); the rest go to the deck bottom in the order given. */
        TAKE_REVEALED_REST_BOTTOM,
        /** Pick between `min` and `max` of `cards` (only `validIds`). */
        PICK,
        /** Yes or no: choosing the card in `cards` means yes, choosing nothing means no. */
        CONFIRM,
    }

    var id: String? = null
    var kind: Kind? = null
    var playerId: String? = null
    var effectId: String? = null
    /**
     * Choices that are not part of an effect: BLOCK (defender), PROVOKE (attacker's Provocar), ADVANCE (move up to
     * the Línea frontal), RITUAL (creatures for a Ritual from the hand), SPECIAL_FRONT (move up right after a
     * special summon), REINFORCE (Reforzar with a life card), LIFE_PLAY (play a utility or structure lost as a
     * life), ORDER (which of your simultaneous triggers activates next), PRIORITY (respond to the chain).
     */
    var purpose: String? = null
    var sourceName: String? = null
    /** The art of the card whose effect asks it, so the player sees at a glance what is resolving. */
    var sourceImageUrl: String? = null
    var title: String? = null
    /** What the player is asked to do, in Spanish. */
    var instruction: String? = null
    /** What happens to a chosen card ("A tu mano", "Tributar", "Destruir"), shown on it. */
    var chosenLabel: String? = null
    var cards: MutableList<CardSlot> = ArrayList()
    /** Cards that may be chosen; the rest are shown but locked. */
    var validIds: MutableList<String?> = ArrayList()
    var min: Int = 0
    var max: Int = 0
    /** The cards that are not chosen can be reordered (they go to the deck bottom in that order). */
    @get:JvmName("isReorderRest")
    var reorderRest: Boolean = false
    /** The cards not chosen go back to the deck top instead of its bottom (in the order given, the first one on top). */
    @get:JvmName("isRestToTop")
    var restToTop: Boolean = false
    /** The card the choice is about (the creature being summoned by Ritual, the life card…). */
    var subjectId: String? = null
    /** Ritual from the hand: the summoner already chosen (dropped onto it). */
    var summonerId: String? = null
    /** What each card stands for in this choice (the effects it triggered), by instance id. */
    var cardLabels: MutableMap<String, String>? = null
    /** The effects offered in this choice, with their whole text (chaining choices). */
    var effects: List<OfferedEffect>? = null
    /** The whole text of the effect this choice belongs to (an effect resolving or paying its cost). */
    var effectText: String? = null
    /** The levels of the cards chosen may add up to this at most (the board locks those that would pass it). */
    var maxLevelSum: Int? = null
    /** Exactly `max` cards or none ("puedes enviar 5 de tus tributos…"): the board does not confirm a number in between. */
    @get:JvmName("isExactOrNone")
    var exactOrNone: Boolean = false
    /** Cards that must be among those chosen ("incluida esta carta"). */
    var requiredIds: List<String>? = null
    /**
     * "envíalas al tope o al fondo de tu mazo": the cards are ordered and sent with one answer, choosing a card (any of
     * `validIds`) sends them to the top, choosing none to the bottom; the order is that of every card.
     */
    @get:JvmName("isTopOrBottom")
    var topOrBottom: Boolean = false

    /** An effect offered to be chained; `effectId` is what the player picks to chain it. */
    /** `inheritedFrom*`: the Recurso that gives it, for an inherited effect. */
    @JvmRecord
    data class OfferedEffect(
        val effectId: String?, val sourceId: String?, val sourceName: String?, val label: String?, val text: String?,
        val inheritedFromName: String?, val inheritedFromImage: String?,
    )

    // --- What Lombok's @Data generated ---

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is Prompt) return false
        if (!other.canEqual(this)) return false
        if (min != other.min) return false
        if (max != other.max) return false
        if (reorderRest != other.reorderRest) return false
        if (restToTop != other.restToTop) return false
        if (exactOrNone != other.exactOrNone) return false
        if (topOrBottom != other.topOrBottom) return false
        if (maxLevelSum != other.maxLevelSum) return false
        if (id != other.id) return false
        if (kind != other.kind) return false
        if (playerId != other.playerId) return false
        if (effectId != other.effectId) return false
        if (purpose != other.purpose) return false
        if (sourceName != other.sourceName) return false
        if (sourceImageUrl != other.sourceImageUrl) return false
        if (title != other.title) return false
        if (instruction != other.instruction) return false
        if (chosenLabel != other.chosenLabel) return false
        if (cards != other.cards) return false
        if (validIds != other.validIds) return false
        if (subjectId != other.subjectId) return false
        if (summonerId != other.summonerId) return false
        if (cardLabels != other.cardLabels) return false
        if (effects != other.effects) return false
        if (effectText != other.effectText) return false
        if (requiredIds != other.requiredIds) return false
        return true
    }

    protected fun canEqual(other: Any?): Boolean = other is Prompt

    override fun hashCode(): Int {
        val PRIME = 59
        var result = 1
        result = result * PRIME + min
        result = result * PRIME + max
        result = result * PRIME + (if (reorderRest) 79 else 97)
        result = result * PRIME + (if (restToTop) 79 else 97)
        result = result * PRIME + (if (exactOrNone) 79 else 97)
        result = result * PRIME + (if (topOrBottom) 79 else 97)
        result = result * PRIME + (maxLevelSum?.hashCode() ?: 43)
        result = result * PRIME + (id?.hashCode() ?: 43)
        result = result * PRIME + (kind?.hashCode() ?: 43)
        result = result * PRIME + (playerId?.hashCode() ?: 43)
        result = result * PRIME + (effectId?.hashCode() ?: 43)
        result = result * PRIME + (purpose?.hashCode() ?: 43)
        result = result * PRIME + (sourceName?.hashCode() ?: 43)
        result = result * PRIME + (sourceImageUrl?.hashCode() ?: 43)
        result = result * PRIME + (title?.hashCode() ?: 43)
        result = result * PRIME + (instruction?.hashCode() ?: 43)
        result = result * PRIME + (chosenLabel?.hashCode() ?: 43)
        result = result * PRIME + cards.hashCode()
        result = result * PRIME + validIds.hashCode()
        result = result * PRIME + (subjectId?.hashCode() ?: 43)
        result = result * PRIME + (summonerId?.hashCode() ?: 43)
        result = result * PRIME + (cardLabels?.hashCode() ?: 43)
        result = result * PRIME + (effects?.hashCode() ?: 43)
        result = result * PRIME + (effectText?.hashCode() ?: 43)
        result = result * PRIME + (requiredIds?.hashCode() ?: 43)
        return result
    }

    override fun toString(): String =
        "Prompt(id=${id}, kind=${kind}, playerId=${playerId}, effectId=${effectId}, purpose=${purpose}, sourceName=${sourceName}, sourceImageUrl=${sourceImageUrl}, title=${title}, instruction=${instruction}, chosenLabel=${chosenLabel}, cards=${cards}, validIds=${validIds}, min=${min}, max=${max}, reorderRest=${reorderRest}, restToTop=${restToTop}, subjectId=${subjectId}, summonerId=${summonerId}, cardLabels=${cardLabels}, effects=${effects}, effectText=${effectText}, maxLevelSum=${maxLevelSum}, exactOrNone=${exactOrNone}, requiredIds=${requiredIds}, topOrBottom=${topOrBottom})"
}
