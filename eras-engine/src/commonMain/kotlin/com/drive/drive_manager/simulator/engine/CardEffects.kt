package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.platform.Platform
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.math.max
import com.drive.drive_manager.simulator.engine.Compose.moveSelfToFront
import com.drive.drive_manager.simulator.engine.Condition.*
import com.drive.drive_manager.simulator.engine.CostModifiers.CostModifier
import com.drive.drive_manager.simulator.engine.EffectScript.Limit
import com.drive.drive_manager.simulator.engine.EffectScript.Moment
import com.drive.drive_manager.simulator.engine.EffectStep.GroupUp
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.Zone

/**
 * Effects implemented by the engine, by card name (reprints keep their effects), plus the keyword abilities
 * it knows. Cards not listed here are still played by hand on the board.
 */
object CardEffects {

    @Suppress("unused")
    private const val TODOS = "Todos Los Turnos"

    /**
     * A card's name as its file has it (lower case), by [key]: the metadata and the image name the same card with or without the
     * printed accents and hyphens ("SAR 2", "SAR-2"), and every printing of a card is the same card (owner's rule, 2026-11-11).
     */
    private val NAME_BY_KEY: MutableMap<String, String> = HashMap()

    private val BY_NAME: MutableMap<String, List<EffectScript>> = HashMap()
    /** Inherited effects ("heredables"): what a card gives the creature it is a Recurso of (rule 11.4.0.3). */
    private val INHERITED: MutableMap<String, List<EffectScript>> = HashMap()
    private val CONTINUOUS: MutableMap<String, List<Continuous>> = HashMap()
    private val INHERITED_CONTINUOUS: MutableMap<String, List<Continuous>> = HashMap()
    /** Enlace: the link effects, what a card gives the members of its formation it points to (Formations). */
    private val FORMATION: MutableMap<String, FormationEffects> = HashMap()

    /**
     * Keyword abilities with an effect the engine runs ("Celeridad"). Others (Provocar, Letalidad…) are combat rules; Asalto modifies the
     * summon itself (Summons.offerAssault).
     */
    private val KEYWORDS: Map<String, EffectScript> = frozenMapOf(
        "celeridad", EffectScript("kw:celeridad", Moment.ON_SUMMON, false, "Celeridad",
            "Puede subir a tu línea frontal.", frozenListOf(moveSelfToFront()))
            .`when`(Condition.not(Condition.inFrontLine())),
        "agrupar 1", EffectScript("kw:agrupar", Moment.ON_ATTACK, false, "Agrupar 1",
            "Si esta carta declara ataque: puedes agotar 1 aliado para que esta carta, durante el ataque, gane su fuerza e inflija daño 1 vez " +
                    "extra en caso de hacerlo.", frozenListOf<EffectStep>(GroupUp(1))))

    @JvmStatic
    fun register(name: String, vararg scripts: EffectScript) {
        BY_NAME[name] = frozenListOf(*scripts)
    }

    /** A Requerimiento programmed by hand for that card name (tests); null removes it. */
    @JvmStatic
    fun registerRequirement(name: String, requirement: RequirementSpec?) {
        if (requirement == null) REQUIREMENTS.remove(name) else REQUIREMENTS[name] = requirement
    }

    /** What identifies a card by its name: [CardKeys.key]. */
    @JvmStatic
    fun key(name: String?): String = CardKeys.key(name)

    /**
     * The programmed card a name stands for: the name its file has (lower case) when one has the same [key], else the name in lower
     * case. Every lookup by name goes through here.
     */
    @JvmStatic
    fun nameOf(cardName: String?): String {
        val plain = cardName?.trim { it <= ' ' }?.lowercase() ?: ""
        return NAME_BY_KEY[key(plain)] ?: plain
    }

    /** The names of the card files (lower case). */
    @JvmStatic
    fun programmedNames(): Set<String> = frozenSetCopy(NAME_BY_KEY.values)

    @JvmStatic
    fun of(cardName: String?): List<EffectScript> {
        if (cardName == null) return frozenListOf()
        return BY_NAME.valueOr(nameOf(cardName), frozenListOf())
    }

    /**
     * A continuous effect: while its condition holds, the cards it affects have that strength and those abilities. It is checked again
     * after every event, so it stops applying as soon as the condition does.
     *
     * @param condition  when it applies; null: always
     * @param zone       where the card has to be for it to apply ("field", or "lifeStack": "mientras esté en tus vidas")
     * @param levelBonus levels it gains while it applies ("gana 1 nivel por cada copia"), or null
     * @param targets    the cards it affects; null: this card
     * @param enemies    `targets` are the opponent's ("los enemigos de fuerza 3 o menos no pueden declarar ataque")
     * @param formationNeighbours `targets` are only the members of its formation next to it ("los aliados [paladín] adyacentes en la formación")
     * @param lives      from the field, it gives `strength` to the creatures in its controller's lives ("las criaturas en tus vidas ganan 20 de
     *                   fuerza"): read when a life's strength is ([lifeStrength])
     * @param effectsSeeStrength "los efectos de esta carta consideran la fuerza de las criaturas reducida en 1" (-1): what the effects of the card
     *                   that has it (its own, inherited and granted ones) read as a creature's strength in their limits ("de fuerza 3 o menos")
     *                   ([EffectEngine.strengthSeen]); 0: none
     * @param ofHolder   with [effectsSeeStrength]: "esta carta [dragón]", only while the card that has it is that; null: any
     */
    @JvmRecord
    data class Continuous(
        val label: String?, val text: String?, val allyTurn: Boolean, val condition: Condition?, val strength: Int, val keywords: List<String>?,
        val bonus: Amount?, val zone: String?, val levelBonus: Amount?, val targets: CardFilter?, val enemies: Boolean,
        val formationNeighbours: Boolean = false, val keywordAmount: Amount? = null, val lives: Boolean = false,
        val effectsSeeStrength: Int = 0, val ofHolder: CardFilter? = null,
    ) {
        /**
         * The keywords it gives now: "gana Letalidad 1 por cada copia de esta carta en tus tributos" is "Letalidad {n}" with
         * [keywordAmount] as n (none when n is 0 or less).
         */
        fun keywordsNow(room: GameRoom, controller: String?): List<String> {
            val all = keywords ?: return frozenListOf()
            if (keywordAmount == null) return all
            val n = keywordAmount.of(room, controller)
            return all.mapNotNull { k -> if (!k.contains("{n}")) k else if (n <= 0) null else k.replace("{n}", n.toString()) }
        }

        constructor(label: String?, text: String?, allyTurn: Boolean, condition: Condition?, strength: Int, keywords: List<String>?,
                    bonus: Amount?, zone: String?, levelBonus: Amount?) :
                this(label, text, allyTurn, condition, strength, keywords, bonus, zone, levelBonus, null, false)

        constructor(label: String?, text: String?, allyTurn: Boolean, condition: Condition?, strength: Int, keywords: List<String>?,
                    bonus: Amount?, zone: String?) :
                this(label, text, allyTurn, condition, strength, keywords, bonus, zone, null)

        constructor(label: String?, text: String?, allyTurn: Boolean, condition: Condition?, strength: Int, keywords: List<String>?,
                    bonus: Amount?) :
                this(label, text, allyTurn, condition, strength, keywords, bonus, "field")

        constructor(label: String?, text: String?, allyTurn: Boolean, condition: Condition?, strength: Int, keywords: List<String>?) :
                this(label, text, allyTurn, condition, strength, keywords, null, "field")

        /** It applies to its holder now: the zone, the turn and the condition. */
        fun holds(room: GameRoom, holder: CardSlot?, controller: String): Boolean {
            if (allyTurn && controller != room.currentTurnUserId) return false
            return condition == null || condition.test(room, null, holder, controller)
        }
    }

    /** The name of the card that gives an inherited effect (its id), or null for a card's own effect. */
    @JvmStatic
    fun inheritedFrom(scriptId: String?): String? {
        for ((key, value) in INHERITED) {
            if (value.any { s -> s.id == scriptId }) return key
        }
        return null
    }

    /** Loads the cards (their static registrations), for registries filled from here such as [CostModifiers]. */
    @JvmStatic
    fun load() {
    }

    /** Everything programmed, card by card (by name in lower case), as data: see [EffectJson]. */
    @JvmStatic
    fun definitions(): Map<String, CardDefinition> {
        // In the order of the names (a TreeSet's): collected, then sorted
        val names: MutableSet<String> = HashSet()
        for (registry in frozenListOf<Map<String, *>>(BY_NAME, INHERITED, CONTINUOUS, REPLACEMENTS, INHERITED_REPLACEMENTS, FORMATION, INHERITED_CONTINUOUS)) {
            names.addAll(registry.keys)
        }
        names.addAll(CostModifiers.names())
        names.addAll(TRAITS.keys)
        names.addAll(REACTIONS.keys)
        names.addAll(REQUIREMENTS.keys)
        val all: MutableMap<String, CardDefinition> = LinkedHashMap()
        for (name in names.sorted()) {
            all[name] = CardDefinition(name, BY_NAME[name], INHERITED[name], CONTINUOUS[name],
                reactionsOf(name), if (INHERITED_REPLACEMENTS.containsKey(name)) frozenListCopy(INHERITED_REPLACEMENTS[name]!!) else null,
                TRAITS[name], FORMATION[name], REQUIREMENTS[name], INHERITED_CONTINUOUS[name])
        }
        return all
    }

    /**
     * Card effects the engine runs as a rule of its own rather than a script, by card name in lower case: "puede
     * considerarse como si estuviera en tu mano" (SPECIAL_FROM_TRIBUTES) and "puede ser tratada como"
     * (ALIASES). With [definitions], everything programmed for a card.
     */
    @JvmStatic
    fun ruleEffects(): Map<String, List<String>> {
        val all: MutableMap<String, MutableList<String>> = HashMap()
        for ((n, t) in TRAITS) {
            val rules = all.getOrPut(n) { ArrayList() }
            if (t.specialFromTributes) rules.add("En Tributos: cuenta como en la mano para invocaciones especiales")
            if (t.oneEffectPerTurn) rules.add("solo 1 de sus efectos por turno")
            if (t.alias != null) rules.add("puede ser tratada como \"" + t.alias + "\"")
            if (t.lifePlayCost != null) rules.add("desde las vidas se juega pagando " + t.lifePlayCost + " gemas")
            if (t.alternativeLevel != null) rules.add("puede ser considerada nivel " + t.alternativeLevel.level)
            if (t.creatureForEffects != null) rules.add("cuenta como criatura de fuerza " + t.creatureForEffects + " para efectos aliados")
            if (t.sacrificeToSummon != null) rules.add("se invoca sacrificando un aliado")
            if (t.fieldPiecesDiscount != null) rules.add("cuesta " + t.fieldPiecesDiscount.reduce + " menos usando " + t.fieldPiecesDiscount.pieces + " criaturas de tu campo")
        }
        for ((n, reactions) in REACTIONS) {
            val rules = all.getOrPut(n) { ArrayList() }
            for (r in reactions) {
                if (r is Reaction.SwapsBlocker) rules.add("cambia al bloqueador declarado por otro aliado")
                if (r is Reaction.AttacksBeforeAscended) rules.add("declara ataque antes de ser ascendida")
                if (r is Reaction.GainBeforeAscended) rules.add("gana " + r.keyword + " antes de ser ascendida")
                if (r is Reaction.EnemyAsTypeForRequirement) rules.add("puede usar un enemigo como [" + r.asType + "] para su Requerimiento")
                if (r is AllyRemovalSummon) rules.add("se invoca especialmente en lugar de que un aliado sea removido")
                if (r is AllyGuard) rules.add("evita que sus aliados sean removidos por efecto oponente")
                if (r is Reaction.TributesInsteadOfDamage) rules.add("se tributa con 2 [retador] en lugar de recibir daño")
                if (r is DamageReplacement) rules.add("se invoca en lugar de recibir " + r.maxDamage + " o menos de daño")
            }
        }
        // In the order of the names, as the TreeMap it was
        val sorted: MutableMap<String, List<String>> = LinkedHashMap()
        for (n in all.keys.sorted()) sorted[n] = all[n]!!
        return sorted
    }

    /** Keyword abilities the engine applies (combat rules, KEYWORDS, Asalto); the rest are played by hand. */
    @JvmField
    val ENGINE_KEYWORDS: Set<String> = frozenSetOf("Provocar", "Enfrentar", "Celeridad", "Asalto", "Furia", "Reactivar",
        "Letalidad", "Imbloqueable", "Perforante", "Alcance", "Protección", "Agrupar", "Golpe rápido")

    private val TRAILING_NUMBER = Platform.regex("\\s*\\d+$")

    /**
     * What of a card's metadata the engine does not run yet (as the vault's status counts it: sync_vault_status.py),
     * or null when everything is programmed. `own`/`inherited`: its effects in the metadata.
     */
    @JvmStatic
    fun pending(cardName: String?, own: Int, inherited: Int, keywords: List<String>): String? = pending(cardName, own, inherited, 0, keywords)

    /** With `formation`: its link effects in the metadata ("Efecto de enlace" and the abilities it gives), counted as the inherited ones. */
    @JvmStatic
    fun pending(cardName: String?, own: Int, inherited: Int, formation: Int, keywords: List<String>): String? =
        pending(cardName, own, inherited, formation, keywords, null)

    /**
     * With `specialKind`: the card's special summon method in the metadata; its Requerimiento must be programmed in the card file (it is
     * never read from the text), else it counts as not programmed.
     */
    @JvmStatic
    fun pending(cardName: String?, own: Int, inherited: Int, formation: Int, keywords: List<String>, specialKind: String?): String? {
        val name = nameOf(cardName)
        val ownDone = BY_NAME.valueOr(name, frozenListOf()).size + CONTINUOUS.valueOr(name, frozenListOf()).size +
                CostModifiers.of(name).size + REPLACEMENTS.valueOr(name, frozenListOf()).size +
                ruleEffects().valueOr(name, frozenListOf()).size
        val inheritedDone = INHERITED.valueOr(name, frozenListOf()).size + INHERITED_REPLACEMENTS.valueOr(name, frozenListOf()).size +
                INHERITED_CONTINUOUS.valueOr(name, frozenListOf()).size
        val pending: MutableList<String> = ArrayList()
        if (own > ownDone) pending.add((own - ownDone).toString() + " de " + own + " efectos propios")
        if (inherited > inheritedDone) pending.add((inherited - inheritedDone).toString() + " de " + inherited + " heredados")
        val link = FORMATION[name]
        val formationDone = if (link == null) 0 else link.effects().size + link.continuous().size + link.reactions().size
        if (formation > formationDone) pending.add((formation - formationDone).toString() + " de " + formation + " de enlace")
        val manual = keywords.map { k -> k.replace(TRAILING_NUMBER, "").trim { it <= ' ' } }
            .filter { k -> !k.isEmpty() && !ENGINE_KEYWORDS.contains(k) }.distinct()
        if (!manual.isEmpty()) pending.add(manual.joinToString(", "))
        if (!specialKind.isNullOrBlank() && !REQUIREMENTS.containsKey(name)) pending.add("Requerimiento de invocación especial")
        return if (pending.isEmpty()) null else pending.joinToString("; ")
    }

    /** What a card gives the creature it is a Recurso of. */
    @JvmStatic
    fun inheritedOf(name: String?): List<EffectScript> {
        return if (name == null) frozenListOf() else INHERITED.valueOr(nameOf(name), frozenListOf())
    }

    /** A creature's strength while it is a life: its own and what its continuous effects of the lives give it. */
    @JvmStatic
    fun lifeStrength(room: GameRoom, owner: String?, life: CardSlot): Int {
        var strength = if (life.strength == null) 0 else life.strength!!
        for (c in continuousOf(life)) {
            if (Zone.LIFE_STACK.`is`(c.zone) && !c.lives && c.condition!!.test(room, null, life, owner)) strength += c.strength
        }
        // "las criaturas en tus vidas ganan N de fuerza" (Lumisdragon Arcano): a card of the owner's field
        val player = room.getPlayer(owner)
        if (player != null && "creature" == life.cardType) for (card in player.field) {
            if (card.faceDown) continue
            for (c in continuousOf(card)) {
                if (c.lives && Zone.FIELD.`is`(c.zone) && (c.condition == null || c.condition.test(room, null, card, owner))) strength += c.strength
            }
        }
        // Helena: "reduce … las de las vidas oponentes en N, hasta el final del turno"
        val reduced = EffectEngine.LIFE_REDUCED + life.instanceId + "|"
        for (k in room.usedThisTurn) if (k.startsWith(reduced)) strength -= k.substring(reduced.length).toInt()
        return strength
    }

    /** A utility whose effect keeps it on the field as a creature ([EffectStep.KeepOnField] with `asCreature`), now a creature. */
    @JvmStatic
    fun creatureOnFieldOnly(card: CardSlot): Boolean {
        return "creature" == card.cardType && of(card.cardName).any { s -> s.steps!!.any { it is EffectStep.KeepOnField && it.asCreature != null } }
    }

    /** "En Tributos: esta carta puede considerarse como si estuviera en tu mano para invocaciones especiales." */
    @JvmStatic
    fun specialFromTributes(card: CardSlot): Boolean {
        return traits(card).specialFromTributes
    }

    private val AGRUPAR: Regex = Platform.regex("agrupar\\s*(\\d+)")

    /** "Agrupar N": up to N allies, Letalidad 1 for each (designer ruling 2026-10-31). Agrupar 1 is the one in KEYWORDS. */
    @JvmStatic
    fun agrupar(allies: Int): EffectScript {
        if (allies == 1) return KEYWORDS["agrupar 1"]!!
        return EffectScript("kw:agrupar-$allies", Moment.ON_ATTACK, false, "Agrupar $allies",
            "Si esta carta declara ataque: puedes agotar hasta " + allies + " aliados para que esta carta, durante el ataque, gane sus fuerzas e " +
                    "inflija daño 1 vez extra por cada uno.", frozenListOf<EffectStep>(GroupUp(allies)))
    }

    /** The card's continuous effects: its own, those its Recursos give it and those of its formation's link. */
    @JvmStatic
    fun continuousOf(card: CardSlot): List<Continuous> {
        val own = if (card.cardName == null) frozenListOf() else CONTINUOUS.valueOr(nameOf(card.cardName), frozenListOf())
        val given = card.resources.orEmpty().filter { it.cardName != null && INHERITED_CONTINUOUS.containsKey(nameOf(it.cardName)) }
        if (card.formationGrants.isNullOrEmpty() && given.isEmpty()) return own
        val all: MutableList<Continuous> = ArrayList(own)
        for (resource in given) all.addAll(INHERITED_CONTINUOUS[nameOf(resource.cardName)]!!)
        for (g in Formations.grants(card.formationGrants)) FORMATION[nameOf(g.sourceName)]?.let { all.addAll(it.continuous()) }
        return all
    }

    /** The link effects (triggered and activated) the grants give ("source|since|name"), each with the id of its grant. */
    @JvmStatic
    fun formationScripts(grants: List<*>?): List<EffectScript> {
        val all: MutableList<EffectScript> = ArrayList()
        for (g in Formations.grants(grants)) {
            val link = FORMATION[nameOf(g.sourceName)] ?: continue
            for (s in link.effects()) all.add(s.copy(id = Formations.grantedId(s.id, g)))
        }
        return all
    }

    /** The name of the card whose link effect it is (the id of a granted copy), or null. */
    @JvmStatic
    fun formationFrom(scriptId: String?): String? {
        if (scriptId == null || !scriptId.contains(Formations.VIA)) return null
        val base = scriptId.substringBefore(Formations.VIA)
        for ((key, value) in FORMATION) {
            if (value.effects().any { it.id == base } || value.reactions().any { (it as? Replacement)?.id == base }) return key
        }
        return null
    }

    /** The card's own effects, those its Recursos give it and those of the keywords the engine runs. */
    @JvmStatic
    fun of(card: CardSlot): List<EffectScript> {
        val all: MutableList<EffectScript> = ArrayList(of(card.cardName))
        for (resource in card.resources!!) {
            if (resource.cardName != null) all.addAll(INHERITED.valueOr(nameOf(resource.cardName), frozenListOf()))
        }
        // Enlace: what the members of its formation that point to it give it
        if (!card.formationGrants.isNullOrEmpty()) all.addAll(formationScripts(card.formationGrants))
        for (keyword in EffectEngine.keywords(card)) {
            var script = KEYWORDS[keyword.lowercase()]
            // An ability gained between «» (GrantEffect)
            if (script == null) script = GrantedEffects.byMarker(keyword)
            // Agrupar N: up to N allies
            val groupUp = AGRUPAR.matchEntire(keyword.lowercase())
            if (script == null && groupUp != null) script = agrupar(groupUp.groupValues[1].toInt())
            if (script != null) all.add(script)
        }
        return all
    }

    /** Looks an effect up by its id (card effects and keywords). */
    @JvmStatic
    fun byId(id: String): EffectScript? {
        if (id.contains(Formations.VIA)) {
            val base = id.substringBefore(Formations.VIA)
            for (link in FORMATION.values) for (s in link.effects()) if (s.id == base) return s.copy(id = id)
            return null
        }
        for (scripts in BY_NAME.values) for (s in scripts) if (s.id == id) return s
        for (scripts in INHERITED.values) for (s in scripts) if (s.id == id) return s
        for (s in KEYWORDS.values) if (s.id == id) return s
        GrantedEffects.byId(id)?.let { return it }
        return if (id.startsWith("kw:agrupar-")) agrupar(id.substring("kw:agrupar-".length).toInt()) else null
    }

    /** Cost to play a utility or structure, after its cost modifiers (they ask for nothing to be paid). */
    @JvmStatic
    fun playCost(room: GameRoom, userId: String?, card: CardSlot): Int {
        val cost = if (card.cost == null) 0 else card.cost!!
        return max(0, cost - CostModifiers.available(room, room.getPlayer(userId), CostModifiers.Event.PLAY, null, card, null))
    }

    // ------------------------------------------------------------------ Replacements ("en su lugar")

    /** What a replacement costs instead of the removal. */
    enum class ReplacementCost {
        /** "puedes tributar 2 materiales de esta carta". */
        TRIBUTE_TWO_MATERIALS,
        /** "puedes tributar 1 carta de tu mano" (the owner picks it). */
        TRIBUTE_ONE_FROM_HAND,
        /** "puedes tributar todos los recursos [filtro] de esta carta" (resourceFilter; any: all of them). */
        TRIBUTE_RESOURCES,
        /** "puedes colocar hasta 2 criaturas [filtro] de los materiales de esta carta en tu retaguardia" (resourceFilter: which ones). */
        MATERIALS_TO_BACK,
        /** "puede tributar los recursos de esta carta" before it is removed (a reaction: it is still removed; they do not go to the Descarte). */
        RESOURCES_TO_TRIBUTES_BEFORE,
        /** "en su lugar, puede invocar especialmente esta carta": a regular special summon of another card with the ally as summoner. */
        ALLY_SPECIAL_SUMMON,
        /** "en su lugar, puedes tributar 1 material de esta carta y sacrificar 1 [filtro] bajo tu control" (resourceFilter: what is sacrificed). */
        GUARD_MATERIAL_AND_SACRIFICE,
        /** "puedes tributar 1 de sus recursos para añadir otro de ellos a tu mano" before it is removed (a reaction: it is still removed). */
        RESOURCE_TRIBUTED_ANOTHER_TO_HAND,
        /**
         * "coloca el recurso de este efecto en tu retaguardia con todos los recursos [filtro] de esta carta como sus recursos" (mandatory: done by
         * the rules before the removal, Removals.reactionsBeforeRemoval; never offered as a choice).
         */
        RESOURCE_TO_BACK_WITH_OTHERS,
        /** "en su lugar, puedes colocar 1 aliado al tope de tus vidas bocabajo" (another ally of its owner; a link effect, Enlace). */
        ALLY_TO_LIFE_FACE_DOWN,
        /** "en su lugar, puedes agotar 1 de tus estructuras" (an active structure of its owner). */
        TAP_STRUCTURE,
        /** A guard ([AllyGuard]): "en su lugar, puedes sacrificar otro aliado o una carta de tu mano" (a creature of its owner's field or a card of the hand). */
        SACRIFICE_OTHER_ALLY_OR_HAND,
        /** "en su lugar, tributa "X" de sus recursos y súbela a tu mano" (resourceFilter: the Recurso tributed, one): the card goes to its owner's hand. */
        TRIBUTE_RESOURCE_RETURN_TO_HAND
    }

    /**
     * "cuando esta carta fuera a ser destruida / removida [por el oponente]: en su lugar, puedes …".
     *
     * @param destroyOnly only when it would be destroyed ("removida" also covers being sent to the hand)
     * @param withMaterials "si esta carta con materiales fuera a ser …": only while it has materials
     */
    @JvmRecord
    data class Replacement(
        val id: String?, val label: String?, val text: String?, val destroyOnly: Boolean, val byOpponentOnly: Boolean, val limit: Limit?,
        val cost: ReplacementCost?, val resourceFilter: CardFilter?, val withMaterials: Boolean = false,
        /** "cuando esta carta [hada] fuera a ser destruida": what the card must be for it to apply; null: anything. */
        val holder: CardFilter? = null,
    ) : Reaction {
        constructor(id: String?, label: String?, text: String?, destroyOnly: Boolean, byOpponentOnly: Boolean, limit: Limit?, cost: ReplacementCost?) :
                this(id, label, text, destroyOnly, byOpponentOnly, limit, cost, CardFilter.any())

        /** "en su lugar": the removal does not happen. A reaction (MATERIALS_TO_BACK, RESOURCES_TO_TRIBUTES_BEFORE) happens before it, and the removal goes on. */
        fun instead(): Boolean {
            return cost != ReplacementCost.MATERIALS_TO_BACK && cost != ReplacementCost.RESOURCES_TO_TRIBUTES_BEFORE &&
                    cost != ReplacementCost.RESOURCE_TRIBUTED_ANOTHER_TO_HAND && cost != ReplacementCost.RESOURCE_TO_BACK_WITH_OTHERS
        }
    }

    private val REPLACEMENTS: MutableMap<String, List<Replacement>> = HashMap()

    /** Replacements a card gives as a Recurso (inherited). */
    private val INHERITED_REPLACEMENTS: MutableMap<String, List<Replacement>> = HashMap()

    /**
     * "cuando algún aliado [filtro] fuera a ser removido: en su lugar, puede invocar especialmente esta carta [reduciendo en N su coste
     * especial]", from the card's zone (the hand, the Tributos). Designer ruling 2026-10-31: it is a regular special summon, paying its cost and
     * with everything its Requerimiento asks; the ally that would be removed is its summoner (Removals.replacementOptions).
     */
    /** What cards change in the rules themselves, by name: [CardTraits]. */
    private val TRAITS: MutableMap<String, CardTraits> = HashMap()

    /** Special summon Requerimientos programmed by hand, by name: [RequirementSpec]. */
    private val REQUIREMENTS: MutableMap<String, RequirementSpec> = HashMap()

    /** The card's Requerimiento programmed by hand (its file's `requirement` section), or null: then it is read from the printed text. */
    @JvmStatic
    fun requirementOf(card: CardSlot?): RequirementSpec? {
        if (card?.cardName == null) return null
        return REQUIREMENTS[nameOf(card.cardName)]
    }

    /** What cards do before an event, other than cost changes and replacements of a removal: [Reaction]. */
    private val REACTIONS: MutableMap<String, List<Reaction>> = HashMap()

    /** Everything the card reacts with. */
    private fun reactionsOf(name: String): List<Reaction> {
        val all: MutableList<Reaction> = ArrayList(CostModifiers.of(name))
        all.addAll(REPLACEMENTS.valueOr(name, frozenListOf()))
        all.addAll(REACTIONS.valueOr(name, frozenListOf()))
        return all
    }

    /** The card's reaction of that kind, or null. */
    private inline fun <reified T : Reaction> reaction(card: CardSlot): T? {
        if (card.cardName == null) return null
        return REACTIONS.valueOr(nameOf(card.cardName), frozenListOf())
            .firstOrNull { it is T } as T?
    }

    private fun traits(card: CardSlot): CardTraits {
        return if (card.cardName == null) CardTraits.NONE
        else TRAITS.valueOr(nameOf(card.cardName), CardTraits.NONE)
    }

    @JvmRecord
    data class AllyRemovalSummon(val zone: String?, val ally: CardFilter?, val reduce: Int, val replacement: Replacement?) : Reaction

    /**
     * "cuando uno o más aliados [filtro] fueran a ser removidos por efecto oponente: en su lugar, puedes …" held by a card on the field (itself
     * included). Designer ruling 2026-10-31: paid once, it saves every one of them that this effect was removing.
     */
    @JvmRecord
    data class AllyGuard(val allies: CardFilter?, val replacement: Replacement?) : Reaction

    @JvmStatic
    fun allyGuard(card: CardSlot): AllyGuard? {
        return reaction<AllyGuard>(card)
    }

    /**
     * "En Mano: cuando fueras a recibir daño: en su lugar, puedes tributar esta carta más 2 cartas [retador] de tu mano, todas de distinto nivel"
     * (designer ruling 2026-10-31: damage of an attack or of an effect; one hit at a time, and Letalidad's next hits do not come after a
     * prevented one). CombatRules.offerDamageTribute.
     */
    @JvmStatic
    fun tributesInsteadOfDamage(card: CardSlot): Boolean {
        return reaction<Reaction.TributesInsteadOfDamage>(card) != null
    }

    /** "esta carta puede ser considerada nivel N por tus cartas [filtro]" (designer ruling 2026-10-31: for everything, special summons and effects). */
    @JvmRecord
    data class AlternativeLevel(val level: Int, val by: CardFilter?)

    /** The other level `card` can count as when `looker` reads it, or null. */
    @JvmStatic
    fun alternativeLevel(card: CardSlot, looker: CardSlot?): Int? {
        val alt = if (looker == null) null else traits(card).alternativeLevel
        return if (alt != null && alt.by!!.matches(looker!!)) alt.level else null
    }

    /** "si utilizas al menos N criaturas en tu campo: reduce el coste especial de esta carta en M" (SpecialRequirements.fieldPiecesDiscount). */
    @JvmRecord
    data class FieldPiecesDiscount(val pieces: Int, val reduce: Int)

    @JvmStatic
    fun fieldPiecesDiscount(card: CardSlot): FieldPiecesDiscount? {
        return traits(card).fieldPiecesDiscount
    }

    /**
     * "esta carta puede ser considerada como una criatura de fuerza N por efectos aliados, pero no puede ser utilizada para invocaciones
     * especiales" (Fósil): the strength it has for the effects that look at it (CardFilter), never for a special summon.
     */
    @JvmStatic
    fun creatureStrengthForEffects(card: CardSlot): Int? {
        return traits(card).creatureForEffects
    }

    /**
     * "Cuando fueras a invocar esta carta: puedes sacrificar 1 aliado [filtro] activo que no sea una copia de esta carta para invocar esta
     * carta en tu línea frontal y reducir su coste en el coste del aliado sacrificado" (Carnirex; Summons.normalSummon).
     */
    @JvmStatic
    fun sacrificeToSummon(card: CardSlot): CardFilter? {
        return traits(card).sacrificeToSummon
    }

    /** How many allies [sacrificeToSummon] may sacrifice at most ("sacrifica hasta 3 aliados"): 1 unless the card says more. */
    @JvmStatic
    fun sacrificeUpTo(card: CardSlot): Int {
        return traits(card).sacrificeUpTo ?: 1
    }

    @JvmStatic
    fun allyRemovalSummon(card: CardSlot): AllyRemovalSummon? {
        return reaction<AllyRemovalSummon>(card)
    }

    /** The card's own replacements and those its Recursos give it. */
    @JvmStatic
    fun replacementsOf(card: CardSlot): List<Replacement> {
        val all: MutableList<Replacement> = ArrayList()
        if (card.cardName != null) all.addAll(REPLACEMENTS.valueOr(nameOf(card.cardName), frozenListOf()))
        for (resource in card.resources!!) {
            if (resource.cardName != null)
                all.addAll(INHERITED_REPLACEMENTS.valueOr(nameOf(resource.cardName), frozenListOf()))
        }
        // Enlace: the replacements the members of its formation that point to it give it, each with the id of its grant
        for (g in Formations.grants(card.formationGrants)) {
            val link = FORMATION[nameOf(g.sourceName)] ?: continue
            for (r in link.reactions()) if (r is Replacement) all.add(r.copy(id = Formations.grantedId(r.id, g)))
        }
        return all
    }

    /** The Recurso of the card that gives it that replacement (inherited), or null when it is the card's own. */
    @JvmStatic
    fun replacementGiver(card: CardSlot, replacement: Replacement?): CardSlot? {
        for (resource in card.resources!!) {
            if (resource.cardName != null &&
                INHERITED_REPLACEMENTS.valueOr(nameOf(resource.cardName), frozenListOf()).contains(replacement)) return resource
        }
        return null
    }

    /** "En Mano: al entrar al paso de bloqueo: puedes jugar esta carta pagando su coste." */
    /** "cuando esta carta fuera a ser ascendida: puede declarar ataque" (SpecialSummons.handSpecial, then completeAscension). */
    @JvmStatic
    fun attacksBeforeAscended(card: CardSlot): Boolean {
        return reaction<Reaction.AttacksBeforeAscended>(card) != null
    }

    /** "Para jugar esta carta desde tus vidas debes pagar N gemas" (an absolute effect: CombatRules.directDamage offers it only then). */
    @JvmStatic
    fun lifePlayCost(card: CardSlot): Int {
        val cost = traits(card).lifePlayCost
        return cost ?: 0
    }

    /** "cuando esta carta fuera a ser ascendida: esta carta gana [habilidad] hasta el final del turno" (Turno Aliado; Summons.specialSummon). */
    @JvmStatic
    fun gainBeforeAscended(card: CardSlot): String? {
        val gain = reaction<Reaction.GainBeforeAscended>(card)
        return gain?.keyword
    }

    /** "puedes utilizar un enemigo considerándolo [tipo] para el requerimiento" (RitualParts). */
    @JvmStatic
    fun enemyAsTypeForRequirement(card: CardSlot): Reaction.EnemyAsTypeForRequirement? {
        return reaction<Reaction.EnemyAsTypeForRequirement>(card)
    }

    /** "cuando un aliado sea declarado bloqueador: en su lugar, puedes reactivarlo y elegir otro aliado …" (CombatRules.offerBlockerSwap). */
    @JvmStatic
    fun swapsBlocker(card: CardSlot): Boolean {
        return reaction<Reaction.SwapsBlocker>(card) != null
    }

    /** "cuando fueras a recibir N o menos de daño directo por ataque: en su lugar, puedes invocar esta carta … reduciendo su coste en M". */
    @JvmRecord
    data class DamageReplacement(val maxDamage: Int, val reduce: Int) : Reaction

    @JvmStatic
    fun damageReplacement(card: CardSlot): DamageReplacement? {
        return reaction<DamageReplacement>(card)
    }

    /** "Esta carta puede ser tratada como «X»": the name it also answers to. */
    /** The card is called `name`, or can be treated as such. */
    @JvmStatic
    fun isNamed(card: CardSlot, name: String?): Boolean {
        if (card.cardName == null || name == null) return false
        val wanted = key(name)
        val alias = traits(card).alias
        return key(card.cardName) == wanted || (alias != null && key(alias) == wanted)
    }

    /** What the files say of a card, to the registries. */
    private fun install(d: CardDefinition) {
        val name = d.name
        val other = NAME_BY_KEY.put(key(name), name!!)
        check(other == null || other == name) { "La misma carta en dos archivos: \"$other\" y \"$name\"" }
        if (!d.effects().isEmpty()) BY_NAME[name!!] = d.effects()
        if (!d.inherited().isEmpty()) INHERITED[name!!] = d.inherited()
        GrantedEffects.register(d.effects())
        GrantedEffects.register(d.inherited())
        if (!d.continuous().isEmpty()) CONTINUOUS[name!!] = d.continuous()
        if (!d.inheritedContinuous().isEmpty()) INHERITED_CONTINUOUS[name!!] = d.inheritedContinuous()
        if (d.traits != null) TRAITS[name!!] = d.traits
        if (d.requirement != null) REQUIREMENTS[name!!] = d.requirement
        val others: MutableList<Reaction> = ArrayList()
        val replacements: MutableList<Replacement> = ArrayList()
        for (r in d.reactions()) {
            if (r is CostModifier) CostModifiers.register(name!!, r)
            else if (r is Replacement) replacements.add(r)
            else others.add(r)
        }
        if (!replacements.isEmpty()) REPLACEMENTS[name!!] = replacements
        if (!others.isEmpty()) REACTIONS[name!!] = others
        val inherited = d.inheritedReactions().map { it as Replacement }
        if (!inherited.isEmpty()) INHERITED_REPLACEMENTS[name!!] = inherited
        if (d.formation != null) {
            FORMATION[name!!] = d.formation
            GrantedEffects.register(d.formation.effects())
        }
    }

    init {
        CardSources.cards().forEach(::install)
    }
}
