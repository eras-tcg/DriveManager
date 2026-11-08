package com.drive.drive_manager.simulator.room

import kotlin.jvm.JvmName
class CardSlot() {
    var instanceId: String? = null
    var cardId: String? = null
    var imageUrl: String? = null
    @get:JvmName("isFaceDown")
    var faceDown: Boolean = false
    @get:JvmName("isTapped")
    var tapped: Boolean = false
    var x: Double? = null
    var y: Double? = null
    var cardName: String? = null
    var cardType: String? = null
    var strength: Int? = null
    var specialSummonKind: String? = null
    @get:JvmName("isStarter")
    var starter: Boolean = false
    var strengthModifier: Int = 0
    /** Printed properties used by the rules engine (null when the card has no metadata). */
    var cost: Int? = null
    private var level: Int? = null
    /** Color identity plus extra colors (B, G, P, R, W). */
    var colors: MutableList<String>? = ArrayList()
    var classes: MutableList<String>? = ArrayList()
    /** Special summon cost and Requerimiento as printed ("criatura nivel 2", "criaturas que en nivel sumen 6"). */
    var specialCost: Int? = null
    var requirement: String? = null
    /** Printed keyword abilities ("Provocar", "Letalidad 1", "Imbloqueable"). */
    var keywords: MutableList<String>? = ArrayList()
    /** Keyword abilities it gives the creature it is a Recurso of ("Reactivar" heredable). */
    var inheritKeywords: MutableList<String>? = ArrayList()
    /** Abilities gained until the end of the turn ("gana Furia 1 hasta el final del turno"). */
    var turnKeywords: MutableList<String>? = ArrayList()
    /** Abilities until the end of the next turn ("hasta el final del turno oponente"): they become turnKeywords when this turn ends. */
    var laterKeywords: MutableList<String>? = ArrayList()
    /** What lasts "hasta el final del turno o de su próximo ataque" ("Provocar", "fuerza:3"): it ends with its next attack too. */
    var untilNextAttack: MutableList<String>? = ArrayList()
    /**
     * Timeline seq when it entered the field: a card on the field only reacts to events from then on (an event
     * that happened before it was there, like the sacrifice of its own Ritual summoner, cannot trigger it).
     */
    var fieldSeq: Long = 0
    /** Strength gained or lost until the end of the turn ("gana fuerza 3 hasta el final del turno"). */
    var turnStrength: Int = 0
    /** Strength lost until the end of the next turn ("hasta el final del turno oponente / del próximo turno"). */
    var laterStrength: Int = 0
    /** Strength and abilities its continuous effects give it while their condition holds (checked after every event). */
    var continuousStrength: Int = 0
    /** Levels its continuous effects give it now ("gana 1 nivel por cada copia"), added to the printed level. */
    var continuousLevel: Int = 0

    /** Its level now: the printed one plus what its continuous effects give it. Every rule reads this one. */
    fun getLevel(): Int? {
        val printed = level
        return if (printed == null) null else printed + continuousLevel
    }

    fun setLevel(level: Int?) {
        this.level = level
    }

    /** The printed level. */
    fun getPrintedLevel(): Int? {
        return level
    }
    /** Placed "hasta el final del turno" (Janne): it goes back to its owner's hand when the turn ends. */
    @get:JvmName("isReturnAtTurnEnd")
    var returnAtTurnEnd: Boolean = false
    var continuousKeywords: MutableList<String>? = ArrayList()
    /** What effects gave or took from it for a while, with their source (shown in its details). */
    var floating: MutableList<FloatingEffect>? = ArrayList()
    /**
     * "no se reactiva al inicio del turno oponente": 2 while the effect's turn lasts, 1 during its owner's next turn
     * (it is not reactivated then), 0 otherwise. Counted down when the turn changes.
     */
    var noUntap: Int = 0
    /** "no se reactiva al inicio del turno oponente": like [noUntap], but only the untap at the start of that turn. */
    var noStartUntap: Int = 0
    var materials: MutableList<CardSlot>? = ArrayList()
    var resources: MutableList<CardSlot>? = ArrayList()
    /** What of its metadata the rules engine does not run (to be played by hand), null when all of it runs. */
    var unprogrammed: String? = null
    /** The [tipos] its effects and inherited effects mention ("con [fauna] en su texto"), in lower case. */
    var mentions: MutableList<String>? = ArrayList()
    /** The shine of this copy from its owner's collection ("foil" or "prism"), null for a plain one. */
    var finish: String? = null
    /** This copy has one of its card's alternative arts (its imageUrl is that art). */
    @get:JvmName("isAlt")
    var alt: Boolean = false
    /** The card's alternative arts (from its metadata), art id → URL: what a copy with one shows. Kept on the server. */
    var altArtUrls: MutableMap<String, String>? = HashMap()

    /** All the fields, in their order (Lombok's @AllArgsConstructor; Jackson lists their properties first, in this order). */
    constructor(
        instanceId: String?,
        cardId: String?,
        imageUrl: String?,
        faceDown: Boolean,
        tapped: Boolean,
        x: Double?,
        y: Double?,
        cardName: String?,
        cardType: String?,
        strength: Int?,
        specialSummonKind: String?,
        starter: Boolean,
        strengthModifier: Int,
        cost: Int?,
        level: Int?,
        colors: MutableList<String>?,
        classes: MutableList<String>?,
        specialCost: Int?,
        requirement: String?,
        keywords: MutableList<String>?,
        inheritKeywords: MutableList<String>?,
        turnKeywords: MutableList<String>?,
        laterKeywords: MutableList<String>?,
        untilNextAttack: MutableList<String>?,
        fieldSeq: Long,
        turnStrength: Int,
        laterStrength: Int,
        continuousStrength: Int,
        continuousLevel: Int,
        returnAtTurnEnd: Boolean,
        continuousKeywords: MutableList<String>?,
        floating: MutableList<FloatingEffect>?,
        noUntap: Int,
        noStartUntap: Int,
        materials: MutableList<CardSlot>?,
        resources: MutableList<CardSlot>?,
        unprogrammed: String?,
        mentions: MutableList<String>?,
        finish: String?,
        alt: Boolean,
        altArtUrls: MutableMap<String, String>?,
    ) : this() {
        this.instanceId = instanceId
        this.cardId = cardId
        this.imageUrl = imageUrl
        this.faceDown = faceDown
        this.tapped = tapped
        this.x = x
        this.y = y
        this.cardName = cardName
        this.cardType = cardType
        this.strength = strength
        this.specialSummonKind = specialSummonKind
        this.starter = starter
        this.strengthModifier = strengthModifier
        this.cost = cost
        this.level = level
        this.colors = colors
        this.classes = classes
        this.specialCost = specialCost
        this.requirement = requirement
        this.keywords = keywords
        this.inheritKeywords = inheritKeywords
        this.turnKeywords = turnKeywords
        this.laterKeywords = laterKeywords
        this.untilNextAttack = untilNextAttack
        this.fieldSeq = fieldSeq
        this.turnStrength = turnStrength
        this.laterStrength = laterStrength
        this.continuousStrength = continuousStrength
        this.continuousLevel = continuousLevel
        this.returnAtTurnEnd = returnAtTurnEnd
        this.continuousKeywords = continuousKeywords
        this.floating = floating
        this.noUntap = noUntap
        this.noStartUntap = noStartUntap
        this.materials = materials
        this.resources = resources
        this.unprogrammed = unprogrammed
        this.mentions = mentions
        this.finish = finish
        this.alt = alt
        this.altArtUrls = altArtUrls
    }

    // --- What Lombok's @Data generated ---

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is CardSlot) return false
        if (!other.canEqual(this)) return false
        if (faceDown != other.faceDown) return false
        if (tapped != other.tapped) return false
        if (starter != other.starter) return false
        if (strengthModifier != other.strengthModifier) return false
        if (fieldSeq != other.fieldSeq) return false
        if (turnStrength != other.turnStrength) return false
        if (laterStrength != other.laterStrength) return false
        if (continuousStrength != other.continuousStrength) return false
        if (continuousLevel != other.continuousLevel) return false
        if (returnAtTurnEnd != other.returnAtTurnEnd) return false
        if (noUntap != other.noUntap) return false
        if (noStartUntap != other.noStartUntap) return false
        if (alt != other.alt) return false
        if ((x as Any?) != (other.x as Any?)) return false
        if ((y as Any?) != (other.y as Any?)) return false
        if (strength != other.strength) return false
        if (cost != other.cost) return false
        if (getLevel() != other.getLevel()) return false
        if (specialCost != other.specialCost) return false
        if (instanceId != other.instanceId) return false
        if (cardId != other.cardId) return false
        if (imageUrl != other.imageUrl) return false
        if (cardName != other.cardName) return false
        if (cardType != other.cardType) return false
        if (specialSummonKind != other.specialSummonKind) return false
        if (colors != other.colors) return false
        if (classes != other.classes) return false
        if (requirement != other.requirement) return false
        if (keywords != other.keywords) return false
        if (inheritKeywords != other.inheritKeywords) return false
        if (turnKeywords != other.turnKeywords) return false
        if (laterKeywords != other.laterKeywords) return false
        if (untilNextAttack != other.untilNextAttack) return false
        if (continuousKeywords != other.continuousKeywords) return false
        if (floating != other.floating) return false
        if (materials != other.materials) return false
        if (resources != other.resources) return false
        if (unprogrammed != other.unprogrammed) return false
        if (mentions != other.mentions) return false
        if (finish != other.finish) return false
        if (altArtUrls != other.altArtUrls) return false
        return true
    }

    protected fun canEqual(other: Any?): Boolean = other is CardSlot

    override fun hashCode(): Int {
        val PRIME = 59
        var result = 1
        result = result * PRIME + (if (faceDown) 79 else 97)
        result = result * PRIME + (if (tapped) 79 else 97)
        result = result * PRIME + (if (starter) 79 else 97)
        result = result * PRIME + strengthModifier
        val h_fieldSeq = fieldSeq
        result = result * PRIME + (h_fieldSeq ushr 32 xor h_fieldSeq).toInt()
        result = result * PRIME + turnStrength
        result = result * PRIME + laterStrength
        result = result * PRIME + continuousStrength
        result = result * PRIME + continuousLevel
        result = result * PRIME + (if (returnAtTurnEnd) 79 else 97)
        result = result * PRIME + noUntap
        result = result * PRIME + noStartUntap
        result = result * PRIME + (if (alt) 79 else 97)
        val h_x: Any? = x
        result = result * PRIME + (h_x?.hashCode() ?: 43)
        val h_y: Any? = y
        result = result * PRIME + (h_y?.hashCode() ?: 43)
        val h_strength: Any? = strength
        result = result * PRIME + (h_strength?.hashCode() ?: 43)
        val h_cost: Any? = cost
        result = result * PRIME + (h_cost?.hashCode() ?: 43)
        val h_level: Any? = getLevel()
        result = result * PRIME + (h_level?.hashCode() ?: 43)
        val h_specialCost: Any? = specialCost
        result = result * PRIME + (h_specialCost?.hashCode() ?: 43)
        val h_instanceId: Any? = instanceId
        result = result * PRIME + (h_instanceId?.hashCode() ?: 43)
        val h_cardId: Any? = cardId
        result = result * PRIME + (h_cardId?.hashCode() ?: 43)
        val h_imageUrl: Any? = imageUrl
        result = result * PRIME + (h_imageUrl?.hashCode() ?: 43)
        val h_cardName: Any? = cardName
        result = result * PRIME + (h_cardName?.hashCode() ?: 43)
        val h_cardType: Any? = cardType
        result = result * PRIME + (h_cardType?.hashCode() ?: 43)
        val h_specialSummonKind: Any? = specialSummonKind
        result = result * PRIME + (h_specialSummonKind?.hashCode() ?: 43)
        val h_colors: Any? = colors
        result = result * PRIME + (h_colors?.hashCode() ?: 43)
        val h_classes: Any? = classes
        result = result * PRIME + (h_classes?.hashCode() ?: 43)
        val h_requirement: Any? = requirement
        result = result * PRIME + (h_requirement?.hashCode() ?: 43)
        val h_keywords: Any? = keywords
        result = result * PRIME + (h_keywords?.hashCode() ?: 43)
        val h_inheritKeywords: Any? = inheritKeywords
        result = result * PRIME + (h_inheritKeywords?.hashCode() ?: 43)
        val h_turnKeywords: Any? = turnKeywords
        result = result * PRIME + (h_turnKeywords?.hashCode() ?: 43)
        val h_laterKeywords: Any? = laterKeywords
        result = result * PRIME + (h_laterKeywords?.hashCode() ?: 43)
        val h_untilNextAttack: Any? = untilNextAttack
        result = result * PRIME + (h_untilNextAttack?.hashCode() ?: 43)
        val h_continuousKeywords: Any? = continuousKeywords
        result = result * PRIME + (h_continuousKeywords?.hashCode() ?: 43)
        val h_floating: Any? = floating
        result = result * PRIME + (h_floating?.hashCode() ?: 43)
        val h_materials: Any? = materials
        result = result * PRIME + (h_materials?.hashCode() ?: 43)
        val h_resources: Any? = resources
        result = result * PRIME + (h_resources?.hashCode() ?: 43)
        val h_unprogrammed: Any? = unprogrammed
        result = result * PRIME + (h_unprogrammed?.hashCode() ?: 43)
        val h_mentions: Any? = mentions
        result = result * PRIME + (h_mentions?.hashCode() ?: 43)
        val h_finish: Any? = finish
        result = result * PRIME + (h_finish?.hashCode() ?: 43)
        val h_altArtUrls: Any? = altArtUrls
        result = result * PRIME + (h_altArtUrls?.hashCode() ?: 43)
        return result
    }

    override fun toString(): String =
        "CardSlot(instanceId=${instanceId}, cardId=${cardId}, imageUrl=${imageUrl}, faceDown=${faceDown}, tapped=${tapped}, x=${x}, y=${y}, cardName=${cardName}, cardType=${cardType}, strength=${strength}, specialSummonKind=${specialSummonKind}, starter=${starter}, strengthModifier=${strengthModifier}, cost=${cost}, level=${getLevel()}, colors=${colors}, classes=${classes}, specialCost=${specialCost}, requirement=${requirement}, keywords=${keywords}, inheritKeywords=${inheritKeywords}, turnKeywords=${turnKeywords}, laterKeywords=${laterKeywords}, untilNextAttack=${untilNextAttack}, fieldSeq=${fieldSeq}, turnStrength=${turnStrength}, laterStrength=${laterStrength}, continuousStrength=${continuousStrength}, continuousLevel=${continuousLevel}, returnAtTurnEnd=${returnAtTurnEnd}, continuousKeywords=${continuousKeywords}, floating=${floating}, noUntap=${noUntap}, noStartUntap=${noStartUntap}, materials=${materials}, resources=${resources}, unprogrammed=${unprogrammed}, mentions=${mentions}, finish=${finish}, alt=${alt}, altArtUrls=${altArtUrls})"
}
