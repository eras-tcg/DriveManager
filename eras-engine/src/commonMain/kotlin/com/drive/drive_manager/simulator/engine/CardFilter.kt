package com.drive.drive_manager.simulator.engine

import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmRecord
import com.drive.drive_manager.simulator.room.CardSlot

/**
 * Which cards an effect may choose ("1 carta Violeta o Blanca", "1 criatura [bestia] nivel 1").
 * Colors and classes are alternatives (any of them), unless `colorAndClass` asks for both; every other limit must
 * hold too. Empty = any card. Plain data, so a card's effects can be stored and read back.
 *
 * `colors`, `classes`, `notColors`, `cardTypes` and `anyOf` are read through [colors], [classes], [notColors], [cardTypes] and
 * [anyOf], which give an unmodifiable copy, empty when absent: what Java's compact constructor stored. A Kotlin record cannot change
 * what its constructor stores, so the defaults are given when read, and [equals], [hashCode] and [toString] use what is read, as a
 * record of the stored defaults would.
 *
 * @param activeOnly    "activo": not exhausted
 * @param exhaustedOnly "agotado"
 * @param minStrength   "de fuerza N o más" (its current strength)
 * @param maxCost       "de coste N o menos"
 * @param name          "llamada X" (by its name, or a name it can be treated as)
 * @param notName       "que no sea X"
 * @param starterOnly   "Iniciador"
 * @param colorAndClass "Violeta [bestia]": one of the colors and one of the classes (not either)
 * @param notColors     "no-blanca": none of these colors
 * @param nameContains  "con "Primera Línea" en su nombre"
 * @param minCost       "de coste N o mayor"
 * @param cardTypes     "utilidad o estructura": one of these types (with `cardType`, both must hold)
 * @param mentions      "con [tipo] en su texto": of that type, with it in its name, or written in its effects or inherited effects
 * @param backOnly      "en tu retaguardia" (not in the Línea frontal)
 * @param anyOf         alternatives that cannot be written as one filter ("1 utilidad Violeta o [brujería]"): it matches when this
 *                      filter and one of them do
 */
@JvmRecord
data class CardFilter(
    private val colors: List<String>?, private val classes: List<String>?, val cardType: String?, val level: Int?, val maxLevel: Int?,
    val maxStrength: Int?, val activeOnly: Boolean, val exhaustedOnly: Boolean, val minLevel: Int?, val minStrength: Int?, val maxCost: Int?,
    val name: String?, val notName: String?, val starterOnly: Boolean, val colorAndClass: Boolean, private val notColors: List<String>?,
    val nameContains: String?, val minCost: Int?, private val cardTypes: List<String>?, private val anyOf: List<CardFilter>?,
    val mentions: String?, val backOnly: Boolean,
) {

    constructor(
        colors: List<String>?, classes: List<String>?, cardType: String?, level: Int?, maxLevel: Int?, maxStrength: Int?,
        activeOnly: Boolean, exhaustedOnly: Boolean, minLevel: Int?, minStrength: Int?, maxCost: Int?, name: String?,
        notName: String?, starterOnly: Boolean, colorAndClass: Boolean,
    ) : this(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength, maxCost, name, notName,
        starterOnly, colorAndClass, frozenListOf(), null, null, frozenListOf(), frozenListOf(), null, false)

    constructor(colors: List<String>?, classes: List<String>?, cardType: String?, level: Int?, maxLevel: Int?) :
            this(colors, classes, cardType, level, maxLevel, null)

    constructor(colors: List<String>?, classes: List<String>?, cardType: String?, level: Int?, maxLevel: Int?, maxStrength: Int?) :
            this(colors, classes, cardType, level, maxLevel, maxStrength, false, false, null, null, null, null, null, false, false)

    // What the record holds, as Java's compact constructor stored it (null: empty)
    private val colorList: List<String> get() = colors ?: frozenListOf()
    private val classList: List<String> get() = classes ?: frozenListOf()
    private val notColorList: List<String> get() = notColors ?: frozenListOf()
    private val cardTypeList: List<String> get() = cardTypes ?: frozenListOf()
    private val anyOfList: List<CardFilter> get() = anyOf ?: frozenListOf()

    fun colors(): List<String> = if (colors == null) frozenListOf() else frozenListCopy(colors)

    fun classes(): List<String> = if (classes == null) frozenListOf() else frozenListCopy(classes)

    fun notColors(): List<String> = if (notColors == null) frozenListOf() else frozenListCopy(notColors)

    fun cardTypes(): List<String> = if (cardTypes == null) frozenListOf() else frozenListCopy(cardTypes)

    fun anyOf(): List<CardFilter> = if (anyOf == null) frozenListOf() else frozenListCopy(anyOf)

    fun withColors(vararg colors: String): CardFilter {
        return CardFilter(frozenListOf(*colors), classList, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
            maxCost, name, notName, starterOnly, colorAndClass, notColorList, nameContains, minCost, cardTypeList, anyOfList, mentions, backOnly)
    }

    fun withClasses(vararg classes: String): CardFilter {
        return CardFilter(colorList, frozenListOf(*classes), cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
            maxCost, name, notName, starterOnly, colorAndClass, notColorList, nameContains, minCost, cardTypeList, anyOfList, mentions, backOnly)
    }

    fun withLevel(level: Int): CardFilter {
        return CardFilter(colorList, classList, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
            maxCost, name, notName, starterOnly, colorAndClass, notColorList, nameContains, minCost, cardTypeList, anyOfList, mentions, backOnly)
    }

    fun withMaxLevel(maxLevel: Int): CardFilter {
        return CardFilter(colorList, classList, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
            maxCost, name, notName, starterOnly, colorAndClass, notColorList, nameContains, minCost, cardTypeList, anyOfList, mentions, backOnly)
    }

    /** "nivel N o mayor". */
    fun withMinLevel(minLevel: Int): CardFilter {
        return CardFilter(colorList, classList, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
            maxCost, name, notName, starterOnly, colorAndClass, notColorList, nameContains, minCost, cardTypeList, anyOfList, mentions, backOnly)
    }

    /** "de fuerza N o menos" (its current strength). */
    fun withMaxStrength(maxStrength: Int): CardFilter {
        return CardFilter(colorList, classList, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
            maxCost, name, notName, starterOnly, colorAndClass, notColorList, nameContains, minCost, cardTypeList, anyOfList, mentions, backOnly)
    }

    /** "de fuerza N o más" (its current strength). */
    fun withMinStrength(minStrength: Int): CardFilter {
        return CardFilter(colorList, classList, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
            maxCost, name, notName, starterOnly, colorAndClass, notColorList, nameContains, minCost, cardTypeList, anyOfList, mentions, backOnly)
    }

    /** "de coste N o menos". */
    fun withMaxCost(maxCost: Int): CardFilter {
        return CardFilter(colorList, classList, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
            maxCost, name, notName, starterOnly, colorAndClass, notColorList, nameContains, minCost, cardTypeList, anyOfList, mentions, backOnly)
    }

    /** "activo": not exhausted. */
    /** Only creatures, on top of what it asks. */
    fun asCreature(): CardFilter {
        return CardFilter(colorList, classList, "creature", level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
            maxCost, name, notName, starterOnly, colorAndClass, notColorList, nameContains, minCost, cardTypeList, anyOfList, mentions, backOnly)
    }

    /** Only structures, on top of what it asks. */
    fun asStructure(): CardFilter {
        return CardFilter(colorList, classList, "structure", level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
            maxCost, name, notName, starterOnly, colorAndClass, notColorList, nameContains, minCost, cardTypeList, anyOfList, mentions, backOnly)
    }

    fun active(): CardFilter {
        return CardFilter(colorList, classList, cardType, level, maxLevel, maxStrength, true, exhaustedOnly, minLevel, minStrength,
            maxCost, name, notName, starterOnly, colorAndClass, notColorList, nameContains, minCost, cardTypeList, anyOfList, mentions, backOnly)
    }

    /** "agotado". */
    fun exhausted(): CardFilter {
        return CardFilter(colorList, classList, cardType, level, maxLevel, maxStrength, activeOnly, true, minLevel, minStrength,
            maxCost, name, notName, starterOnly, colorAndClass, notColorList, nameContains, minCost, cardTypeList, anyOfList, mentions, backOnly)
    }

    /** "llamada X" (by its name). */
    fun named(name: String?): CardFilter {
        return CardFilter(colorList, classList, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
            maxCost, name, notName, starterOnly, colorAndClass, notColorList, nameContains, minCost, cardTypeList, anyOfList, mentions, backOnly)
    }

    /** "que no sea X". */
    fun notNamed(notName: String?): CardFilter {
        return CardFilter(colorList, classList, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
            maxCost, name, notName, starterOnly, colorAndClass, notColorList, nameContains, minCost, cardTypeList, anyOfList, mentions, backOnly)
    }

    /** "Iniciador". */
    fun starter(): CardFilter {
        return CardFilter(colorList, classList, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
            maxCost, name, notName, true, colorAndClass, notColorList, nameContains, minCost, cardTypeList, anyOfList, mentions, backOnly)
    }

    /** "Violeta [bestia]": the color and one of the classes (not either). */
    fun colorAndClass(color: String, vararg classes: String): CardFilter {
        return CardFilter(frozenListOf(color), frozenListOf(*classes), cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel,
            minStrength, maxCost, name, notName, starterOnly, true, notColorList, nameContains, minCost, cardTypeList, anyOfList, mentions, backOnly)
    }

    /** "no-blanca": none of these colors. */
    fun notColors(vararg notColors: String): CardFilter {
        return CardFilter(colorList, classList, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
            maxCost, name, notName, starterOnly, colorAndClass, frozenListOf(*notColors), nameContains, minCost, cardTypeList, anyOfList, mentions, backOnly)
    }

    /** "con "X" en su nombre". */
    fun nameContains(text: String?): CardFilter {
        return CardFilter(colorList, classList, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
            maxCost, name, notName, starterOnly, colorAndClass, notColorList, text, minCost, cardTypeList, anyOfList, mentions, backOnly)
    }

    /** "de coste N o mayor". */
    fun withMinCost(minCost: Int): CardFilter {
        return CardFilter(colorList, classList, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
            maxCost, name, notName, starterOnly, colorAndClass, notColorList, nameContains, minCost, cardTypeList, anyOfList, mentions, backOnly)
    }

    /** "utilidad", "estructura", "utilidad o estructura" ("creature", "utility", "structure"). */
    fun ofTypes(vararg types: String): CardFilter {
        return CardFilter(colorList, classList, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
            maxCost, name, notName, starterOnly, colorAndClass, notColorList, nameContains, minCost, frozenListOf(*types), anyOfList, mentions, backOnly)
    }

    /** One of these, besides everything this filter asks ("1 utilidad Violeta o [brujería]"). */
    fun orAnyOf(vararg alternatives: CardFilter): CardFilter {
        return CardFilter(colorList, classList, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
            maxCost, name, notName, starterOnly, colorAndClass, notColorList, nameContains, minCost, cardTypeList, frozenListOf(*alternatives), mentions,
            backOnly)
    }

    /** "1 estructura": any card of those types. */
    /** "con [tipo] en su texto". */
    fun mentioning(type: String?): CardFilter {
        return CardFilter(colorList, classList, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
            maxCost, name, notName, starterOnly, colorAndClass, notColorList, nameContains, minCost, cardTypeList, anyOfList, type, backOnly)
    }

    /** "en tu retaguardia". */
    fun inBack(): CardFilter {
        return CardFilter(colorList, classList, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
            maxCost, name, notName, starterOnly, colorAndClass, notColorList, nameContains, minCost, cardTypeList, anyOfList, mentions, true)
    }

    fun matches(card: CardSlot): Boolean {
        // "puede ser considerada como una criatura de fuerza N por efectos aliados" (Fósil)
        val asCreature = EffectEngine.creatureForEffects(card)
        if (cardType != null && cardType != card.cardType && !("creature" == cardType && asCreature != null)) return false
        val cardColors: List<String> = card.colors ?: frozenListOf()
        val cardClasses: List<String> = card.classes ?: frozenListOf()
        val colorMatch = colorList.any { cardColors.contains(it) }
        val classMatch = classList.any { c -> cardClasses.any { cc -> cc.equals(c, ignoreCase = true) } }
        if (colorAndClass) {
            if (!colorMatch || !classMatch) return false
        } else if (!(colorList.isEmpty() && classList.isEmpty()) && !colorMatch && !classMatch) {
            return false
        }
        // Any level it can count as for the card looking (Abraza Almas)
        if ((level != null || maxLevel != null || minLevel != null) && EffectEngine.levels(card)
                .none { l -> (level == null || level == l) && (maxLevel == null || l!! <= maxLevel) && (minLevel == null || l!! >= minLevel) }) return false
        val strength = if (asCreature != null && card.strength == null) asCreature else EffectEngine.strength(card)
        if (maxStrength != null && strength > maxStrength) return false
        if (minStrength != null && strength < minStrength) return false
        if (maxCost != null && (card.cost == null || card.cost!! > maxCost)) return false
        if (activeOnly && card.tapped) return false
        if (exhaustedOnly && !card.tapped) return false
        if (name != null && !CardEffects.isNamed(card, name)) return false
        if (notName != null && notName.equals(card.cardName, ignoreCase = true)) return false
        if (!notColorList.isEmpty() && notColorList.any { cardColors.contains(it) }) return false
        if (nameContains != null && (card.cardName == null ||
                    !card.cardName!!.lowercase().contains(nameContains.lowercase()))) return false
        if (minCost != null && (card.cost == null || card.cost!! < minCost)) return false
        if (!cardTypeList.isEmpty() && !cardTypeList.contains(card.cardType) && !(cardTypeList.contains("creature") && asCreature != null)) return false
        if (!anyOfList.isEmpty() && anyOfList.none { f -> f.matches(card) }) return false
        if (mentions != null) {
            val t = mentions.lowercase()
            val ofType = card.classes!!.any { c -> c.equals(t, ignoreCase = true) }
            val inName = card.cardName != null && card.cardName!!.lowercase().contains(t)
            if (!ofType && !inName && (card.mentions == null || !card.mentions!!.contains(t))) return false
        }
        if (backOnly && EffectEngine.inFrontLine(card)) return false
        return !starterOnly || card.starter
    }

    /** Spanish description for prompts ("Violeta o Blanca", "[bestia] nivel 1"). */
    fun describe(): String {
        val parts: MutableList<String> = ArrayList()
        if (!colorAndClass) {
            for (c in colorList) parts.add(colorName(c))
            for (c in classList) parts.add("[$c]")
        }
        var text = parts.joinToString(" o ")
        if (level != null) text += " nivel $level"
        if (maxLevel != null) text += " nivel $maxLevel o menor"
        if (maxStrength != null) text += " de fuerza $maxStrength o menos"
        if (activeOnly) text += " activo"
        if (colorAndClass) text += " " + colorList.map { colorName(it) }.joinToString(" o ") + " " +
                classList.map { c -> "[$c]" }.joinToString(" o ")
        if (exhaustedOnly) text += " agotado"
        if (minStrength != null) text += " de fuerza $minStrength o más"
        if (name != null) text += " \"$name\""
        if (notName != null) text += " que no sea \"$notName\""
        if (minLevel != null) text += " nivel $minLevel o mayor"
        if (maxCost != null) text += " de coste $maxCost o menos"
        if (starterOnly) text += " Iniciador"
        if (!cardTypeList.isEmpty()) text = cardTypeList.map { typeName(it) }.joinToString(" o ") + " " + text
        if (!notColorList.isEmpty()) text += " " + notColorList.map { c -> "no-" + colorName(c).lowercase() }.joinToString(" ")
        if (nameContains != null) text += " con \"$nameContains\" en su nombre"
        if (minCost != null) text += " de coste $minCost o mayor"
        if (!anyOfList.isEmpty()) text += " " + anyOfList.map { it.describe() }.joinToString(" o ")
        if (mentions != null) text += " con [$mentions] en su texto"
        if (backOnly) text += " en tu retaguardia"
        return text.trim { it <= ' ' }
    }

    /** As a record's: every component, in order, with the lists as read. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CardFilter) return false
        return colorList == other.colorList && classList == other.classList && cardType == other.cardType && level == other.level &&
                maxLevel == other.maxLevel && maxStrength == other.maxStrength && activeOnly == other.activeOnly &&
                exhaustedOnly == other.exhaustedOnly && minLevel == other.minLevel && minStrength == other.minStrength &&
                maxCost == other.maxCost && name == other.name && notName == other.notName && starterOnly == other.starterOnly &&
                colorAndClass == other.colorAndClass && notColorList == other.notColorList && nameContains == other.nameContains &&
                minCost == other.minCost && cardTypeList == other.cardTypeList && anyOfList == other.anyOfList && mentions == other.mentions &&
                backOnly == other.backOnly
    }

    /** As a record's: `31 * h + hash(component)` over the components, in order. */
    override fun hashCode(): Int {
        var result = 0
        for (c in arrayOf<Any?>(colorList, classList, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
            maxCost, name, notName, starterOnly, colorAndClass, notColorList, nameContains, minCost, cardTypeList, anyOfList, mentions, backOnly)) {
            result = 31 * result + (c?.hashCode() ?: 0)
        }
        return result
    }

    override fun toString(): String {
        return "CardFilter[colors=$colorList, classes=$classList, cardType=$cardType, level=$level, maxLevel=$maxLevel, " +
                "maxStrength=$maxStrength, activeOnly=$activeOnly, exhaustedOnly=$exhaustedOnly, minLevel=$minLevel, " +
                "minStrength=$minStrength, maxCost=$maxCost, name=$name, notName=$notName, starterOnly=$starterOnly, " +
                "colorAndClass=$colorAndClass, notColors=$notColorList, nameContains=$nameContains, minCost=$minCost, " +
                "cardTypes=$cardTypeList, anyOf=$anyOfList, mentions=$mentions, backOnly=$backOnly]"
    }

    companion object {
        @JvmStatic
        fun any(): CardFilter {
            return CardFilter(frozenListOf(), frozenListOf(), null, null, null)
        }

        @JvmStatic
        fun colors(vararg colors: String): CardFilter {
            return CardFilter(frozenListOf(*colors), frozenListOf(), null, null, null)
        }

        @JvmStatic
        fun creatures(): CardFilter {
            return CardFilter(frozenListOf(), frozenListOf(), "creature", null, null)
        }

        @JvmStatic
        fun type(vararg types: String): CardFilter {
            return any().ofTypes(*types)
        }

        @JvmStatic
        fun typeName(type: String): String {
            return when (type) {
                "creature" -> "criatura"
                "utility" -> "utilidad"
                "structure" -> "estructura"
                else -> type
            }
        }

        @JvmStatic
        fun colorName(code: String): String {
            return when (code) {
                "B" -> "Azul"
                "G" -> "Verde"
                "P" -> "Violeta"
                "R" -> "Roja"
                "W" -> "Blanca"
                else -> code
            }
        }
    }
}
