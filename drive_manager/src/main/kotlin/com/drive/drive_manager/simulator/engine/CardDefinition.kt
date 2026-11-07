package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.engine.CardEffects.Continuous

/**
 * Everything the engine runs for one card, as data: what [EffectJson] writes and reads, so it can be kept with the
 * card in the database.
 *
 * The lists are read through [effects], [inherited], [continuous], [reactions] and [inheritedReactions], which give an unmodifiable
 * copy, empty when absent: what Java's compact constructor stored. A Kotlin record cannot change what its constructor stores, so the
 * defaults are given when read, and [equals], [hashCode] and [toString] use what is read, as a record of the stored defaults would.
 *
 * @param name                  the card's name, in lower case (how the engine finds it)
 * @param effects               its own effects
 * @param inherited             what it gives the creature it is a Recurso of
 * @param continuous            its continuous effects, on itself or on other cards
 * @param reactions             what it does before an event ("cuando … fuera a …")
 * @param inheritedReactions    the reactions it gives as a Recurso
 * @param traits                what it changes in the rules themselves; null: nothing
 */
@JvmRecord
data class CardDefinition(
    val name: String?, private val effects: List<EffectScript>?, private val inherited: List<EffectScript>?,
    private val continuous: List<Continuous>?, private val reactions: List<Reaction>?, private val inheritedReactions: List<Reaction>?,
    val traits: CardTraits?,
) {

    fun effects(): List<EffectScript> = if (effects == null) java.util.List.of() else java.util.List.copyOf(effects)

    fun inherited(): List<EffectScript> = if (inherited == null) java.util.List.of() else java.util.List.copyOf(inherited)

    fun continuous(): List<Continuous> = if (continuous == null) java.util.List.of() else java.util.List.copyOf(continuous)

    fun reactions(): List<Reaction> = if (reactions == null) java.util.List.of() else java.util.List.copyOf(reactions)

    fun inheritedReactions(): List<Reaction> = if (inheritedReactions == null) java.util.List.of() else java.util.List.copyOf(inheritedReactions)

    /** As a record's: every component, in order, with the lists as read. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CardDefinition) return false
        return name == other.name && effects() == other.effects() && inherited() == other.inherited() && continuous() == other.continuous() &&
                reactions() == other.reactions() && inheritedReactions() == other.inheritedReactions() && traits == other.traits
    }

    /** As a record's: `31 * h + hash(component)` over the components, in order. */
    override fun hashCode(): Int {
        var result = 0
        for (c in arrayOf<Any?>(name, effects(), inherited(), continuous(), reactions(), inheritedReactions(), traits)) {
            result = 31 * result + (c?.hashCode() ?: 0)
        }
        return result
    }

    override fun toString(): String {
        return "CardDefinition[name=$name, effects=${effects()}, inherited=${inherited()}, continuous=${continuous()}, " +
                "reactions=${reactions()}, inheritedReactions=${inheritedReactions()}, traits=$traits]"
    }
}
