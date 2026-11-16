package com.drive.drive_manager.simulator.engine

import kotlin.jvm.JvmRecord
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
 * @param requirement           its special summon's Requerimiento programmed by hand ([RequirementSpec]); null: read from the printed text
 * @param formation             Enlace: its link effect ("Efecto de enlace"), what it gives the members of its formation it points to; null: none
 */
@JvmRecord
data class CardDefinition(
    val name: String?, private val effects: List<EffectScript>?, private val inherited: List<EffectScript>?,
    private val continuous: List<Continuous>?, private val reactions: List<Reaction>?, private val inheritedReactions: List<Reaction>?,
    val traits: CardTraits?, val formation: FormationEffects? = null, val requirement: RequirementSpec? = null,
) {

    fun effects(): List<EffectScript> = if (effects == null) frozenListOf() else frozenListCopy(effects)

    fun inherited(): List<EffectScript> = if (inherited == null) frozenListOf() else frozenListCopy(inherited)

    fun continuous(): List<Continuous> = if (continuous == null) frozenListOf() else frozenListCopy(continuous)

    fun reactions(): List<Reaction> = if (reactions == null) frozenListOf() else frozenListCopy(reactions)

    fun inheritedReactions(): List<Reaction> = if (inheritedReactions == null) frozenListOf() else frozenListCopy(inheritedReactions)

    /** As a record's: every component, in order, with the lists as read. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CardDefinition) return false
        return name == other.name && effects() == other.effects() && inherited() == other.inherited() && continuous() == other.continuous() &&
                reactions() == other.reactions() && inheritedReactions() == other.inheritedReactions() && traits == other.traits &&
                formation == other.formation && requirement == other.requirement
    }

    /** As a record's: `31 * h + hash(component)` over the components, in order. */
    override fun hashCode(): Int {
        var result = 0
        for (c in arrayOf<Any?>(name, effects(), inherited(), continuous(), reactions(), inheritedReactions(), traits, formation)) {
            result = 31 * result + (c?.hashCode() ?: 0)
        }
        // Only when there is one, so the cards without it keep their hash
        if (requirement != null) result = 31 * result + requirement.hashCode()
        return result
    }

    override fun toString(): String {
        return "CardDefinition[name=$name, effects=${effects()}, inherited=${inherited()}, continuous=${continuous()}, " +
                "reactions=${reactions()}, inheritedReactions=${inheritedReactions()}, traits=$traits, formation=$formation" +
                (if (requirement != null) ", requirement=$requirement]" else "]")
    }
}

/**
 * Enlace: a card's link effect ("Efecto de enlace"). Its active card gives it to the members of its formation it points to, which run it as
 * their own ("esta carta" is the receiver): triggered or activated effects, continuous ones (`targets` with `formationNeighbours`: the
 * receiver's members next to it), and reactions (replacements). The lists are read as [CardDefinition]'s: empty when absent.
 */
@JvmRecord
data class FormationEffects(
    private val effects: List<EffectScript>?, private val continuous: List<Continuous>?, private val reactions: List<Reaction>?,
) {

    fun effects(): List<EffectScript> = if (effects == null) frozenListOf() else frozenListCopy(effects)

    fun continuous(): List<Continuous> = if (continuous == null) frozenListOf() else frozenListCopy(continuous)

    fun reactions(): List<Reaction> = if (reactions == null) frozenListOf() else frozenListCopy(reactions)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FormationEffects) return false
        return effects() == other.effects() && continuous() == other.continuous() && reactions() == other.reactions()
    }

    override fun hashCode(): Int {
        var result = 0
        for (c in arrayOf<Any?>(effects(), continuous(), reactions())) result = 31 * result + (c?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "FormationEffects[effects=${effects()}, continuous=${continuous()}, reactions=${reactions()}]"
}
