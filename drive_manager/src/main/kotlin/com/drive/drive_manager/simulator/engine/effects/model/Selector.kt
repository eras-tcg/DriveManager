package com.drive.drive_manager.simulator.engine.effects.model

import com.drive.drive_manager.simulator.engine.Amount
import com.drive.drive_manager.simulator.engine.CardFilter

/**
 * Which cards an action works with: every choice of cards of the game is described this way.
 *
 * `filter`, `self` and `mode` are read through [filter], [self] and [mode], which give their defaults when absent: what Java's
 * compact constructor did. A Kotlin record cannot change what its constructor stores, so the defaults are given when read, and
 * [equals], [hashCode] and [toString] use what is read, as a record of the stored defaults would.
 *
 * @param zones  comma separated: hand, deck, tributeZone, discardPile, lifeStack, field, frontLine, backLine, materials and resources
 *               (of the effect's card), materials:subject and resources:subject (of the ally chosen before), allies (your creatures on the
 *               field), subjectOrSelf (the card chosen before, or this card), eventOnField, looked (the cards an effect is looking at), saved:x (what the memory has under x), event (the card of the triggering event)
 * @param owner  whose zones
 * @param filter which of their cards
 * @param min    fewest to pick: in a cost it must be met in full or the effect is not activated; at resolution as many as there are
 * @param max    most to pick
 * @param self   what happens with the effect's own card
 * @param mode   how they are decided
 * @param relative what it asks in relation to another card or to the game; null: nothing
 * @param special  a choice of a special summon; null: an ordinary one
 */
@JvmRecord
data class Selector(
    val zones: String?, val owner: Owner?, private val filter: CardFilter?, val min: Amount?, val max: Amount?, private val self: Self?,
    private val mode: Mode?, val relative: Relative?, val special: Special?,
) {

    /** The choices of a special summon by effect. */
    enum class Stage { CARD, SUMMONER, MATERIAL }

    @JvmRecord
    data class Special(val rule: SpecialRule?, val stage: Stage?)

    constructor(zones: String?, owner: Owner?, filter: CardFilter?, min: Amount?, max: Amount?, self: Self?, mode: Mode?, relative: Relative?) :
            this(zones, owner, filter, min, max, self, mode, relative, null)

    constructor(zones: String?, owner: Owner?, filter: CardFilter?, min: Amount?, max: Amount?, self: Self?, mode: Mode?) :
            this(zones, owner, filter, min, max, self, mode, null, null)

    /** Which of their cards; none said: any. */
    fun filter(): CardFilter = filter ?: CardFilter.any()

    /** What happens with the effect's own card; none said: [Self.ANY]. */
    fun self(): Self = self ?: Self.ANY

    /** How they are decided; none said: [Mode.CHOOSE]. */
    fun mode(): Mode = mode ?: Mode.CHOOSE

    enum class Compare { EQUAL, AT_MOST, LESS, GREATER }

    /**
     * "de nivel menor que el aliado destruido", "del mismo nivel, pero distinto nombre", "con fuerza menor a esta carta".
     *
     * @param to            the other card: self, subject, last or event; null when only `level` and `maxLevel` are used
     * @param compareLevel  its level against that card's plus `plus`; null: not asked
     * @param compareStrength its strength against that card's; null: not asked
     * @param differentName "pero distinto nombre"
     * @param sameName      "copias de esta carta"
     * @param sharesClass   "que comparta tipo con"
     * @param level         exactly this level, read when it is used; null: not asked
     * @param maxLevel      at most this level, read when it is used; null: not asked
     */
    @JvmRecord
    data class Relative(
        val to: String?, val compareLevel: Compare?, val plus: Int, val compareStrength: Compare?, val differentName: Boolean,
        val sameName: Boolean, val sharesClass: Boolean, val level: Amount?, val maxLevel: Amount?,
    ) {
        companion object {
            @JvmStatic
            fun level(to: String?, compare: Compare?, plus: Int): Relative {
                return Relative(to, compare, plus, null, false, false, false, null, null)
            }

            @JvmStatic
            fun weakerThan(to: String?): Relative {
                return Relative(to, null, 0, Compare.LESS, false, false, false, null, null)
            }

            @JvmStatic
            fun copiesOf(to: String?): Relative {
                return Relative(to, null, 0, null, false, true, false, null, null)
            }

            /** "del mismo nivel, pero distinto nombre". */
            @JvmStatic
            fun sameLevelOtherName(to: String?): Relative {
                return Relative(to, Compare.EQUAL, 0, null, true, false, false, null, null)
            }

            /** "de nivel igual o menor, pero distinto nombre". */
            @JvmStatic
            fun upToLevelOtherName(to: String?): Relative {
                return Relative(to, Compare.AT_MOST, 0, null, true, false, false, null, null)
            }

            @JvmStatic
            fun read(level: Amount?, maxLevel: Amount?): Relative {
                return Relative(null, null, 0, null, false, false, false, level, maxLevel)
            }
        }
    }

    enum class Owner { YOU, OPPONENT, BOTH }

    enum class Self {
        ANY,
        /** "otra", "otras": never the effect's own card. */
        EXCLUDE,
        /** "incluida esta carta": it must be among those picked, and counts whatever the filter says. */
        REQUIRE
    }

    enum class Mode {
        /** The player picks. */
        CHOOSE,
        /** The one that reached the zone last: "esta carta", said by what it gave as a Recurso, just discarded. */
        NEWEST,
        /** The first ones of the zones, in their order ("del tope de tu mazo"). */
        TOP,
        /** All that match. */
        ALL,
        /** The effect's own card, while it is in one of the zones. */
        SELF
    }

    /** "hasta N": none is fine. */
    fun upTo(): Selector {
        return atLeast(0)
    }

    fun atLeast(min: Int): Selector {
        return Selector(zones, owner, filter(), Amount.Fixed(min), max, self(), mode(), relative, special)
    }

    fun with(self: Self?): Selector {
        return Selector(zones, owner, filter(), min, max, self, mode(), relative, special)
    }

    fun `as`(mode: Mode?): Selector {
        return Selector(zones, owner, filter(), min, max, self(), mode, relative, special)
    }

    fun with(relative: Relative?): Selector {
        return Selector(zones, owner, filter(), min, max, self(), mode(), relative, special)
    }

    /** No limit on how many. */
    fun upToAll(): Selector {
        return Selector(zones, owner, filter(), min, Amount.Fixed(Int.MAX_VALUE), self(), mode(), relative, special)
    }

    /** It acts on what an action before it left in the memory, which is not known until then. */
    fun fromMemory(): Boolean {
        return zones!!.startsWith("saved:")
    }

    /** It depends on a card chosen by an action before it, not chosen yet. */
    fun waitsFor(name: String): Boolean {
        return zones!!.contains(":$name") || (relative != null && name == relative.to)
    }

    /** As a record's: every component, in order, with `filter`, `self` and `mode` as read. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Selector) return false
        return zones == other.zones && owner == other.owner && filter() == other.filter() && min == other.min && max == other.max &&
                self() == other.self() && mode() == other.mode() && relative == other.relative && special == other.special
    }

    /** As a record's: `31 * h + hash(component)` over the components, in order. */
    override fun hashCode(): Int {
        var result = 0
        for (c in arrayOf<Any?>(zones, owner, filter(), min, max, self(), mode(), relative, special)) result = 31 * result + (c?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "Selector[zones=$zones, owner=$owner, filter=${filter()}, min=$min, max=$max, self=${self()}, mode=${mode()}, " +
                "relative=$relative, special=$special]"
    }

    companion object {
        /** What a special summon's choice offers is said by its rule. */
        @JvmStatic
        fun of(rule: SpecialRule, stage: Stage?): Selector {
            return Selector(rule.zone, Owner.YOU, CardFilter.any(), Amount.Fixed(0), Amount.Fixed(1), Self.ANY, Mode.CHOOSE, null, Special(rule, stage))
        }

        /** Exactly `count` of yours, which the player picks. */
        @JvmStatic
        fun yours(zones: String?, filter: CardFilter?, count: Int): Selector {
            return Selector(zones, Owner.YOU, filter, Amount.Fixed(count), Amount.Fixed(count), Self.ANY, Mode.CHOOSE)
        }

        /** Exactly `count` enemies: the opponent's Línea frontal. */
        @JvmStatic
        fun enemies(filter: CardFilter?, count: Int): Selector {
            return Selector("frontLine", Owner.OPPONENT, filter, Amount.Fixed(count), Amount.Fixed(count), Self.ANY, Mode.CHOOSE)
        }

        /** All of yours that match. */
        @JvmStatic
        fun all(zones: String?, filter: CardFilter?): Selector {
            return Selector(zones, Owner.YOU, filter, Amount.Fixed(0), Amount.Fixed(Int.MAX_VALUE), Self.ANY, Mode.ALL)
        }

        /** The effect's own card, while it is in one of these zones. */
        @JvmStatic
        fun self(zones: String?): Selector {
            return Selector(zones, Owner.YOU, CardFilter.any(), Amount.Fixed(1), Amount.Fixed(1), Self.ANY, Mode.SELF)
        }

        /** All that the memory has under that name, without asking. */
        @JvmStatic
        fun saved(name: String?): Selector {
            return all("saved:$name", CardFilter.any())
        }
    }
}
