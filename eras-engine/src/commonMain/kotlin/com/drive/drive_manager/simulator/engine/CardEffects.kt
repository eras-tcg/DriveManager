package com.drive.drive_manager.simulator.engine

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

    private val BY_NAME: MutableMap<String, List<EffectScript>> = HashMap()
    /** Inherited effects ("heredables"): what a card gives the creature it is a Recurso of (rule 11.4.0.3). */
    private val INHERITED: MutableMap<String, List<EffectScript>> = HashMap()
    private val CONTINUOUS: MutableMap<String, List<Continuous>> = HashMap()

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

    @JvmStatic
    fun of(cardName: String?): List<EffectScript> {
        if (cardName == null) return frozenListOf()
        return BY_NAME.valueOr(cardName.trim { it <= ' ' }.lowercase(), frozenListOf())
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
     */
    @JvmRecord
    data class Continuous(
        val label: String?, val text: String?, val allyTurn: Boolean, val condition: Condition?, val strength: Int, val keywords: List<String>?,
        val bonus: Amount?, val zone: String?, val levelBonus: Amount?, val targets: CardFilter?, val enemies: Boolean,
    ) {
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
        for (registry in frozenListOf<Map<String, *>>(BY_NAME, INHERITED, CONTINUOUS, REPLACEMENTS, INHERITED_REPLACEMENTS)) {
            names.addAll(registry.keys)
        }
        names.addAll(CostModifiers.names())
        names.addAll(TRAITS.keys)
        names.addAll(REACTIONS.keys)
        val all: MutableMap<String, CardDefinition> = LinkedHashMap()
        for (name in names.sorted()) {
            all[name] = CardDefinition(name, BY_NAME[name], INHERITED[name], CONTINUOUS[name],
                reactionsOf(name), if (INHERITED_REPLACEMENTS.containsKey(name)) frozenListCopy(INHERITED_REPLACEMENTS[name]!!) else null,
                TRAITS[name])
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
        "Letalidad", "Imbloqueable", "Perforante", "Alcance", "Protección", "Agrupar")

    private val TRAILING_NUMBER = Regex("\\s*\\d+$")

    /**
     * What of a card's metadata the engine does not run yet (as the vault's status counts it: sync_vault_status.py),
     * or null when everything is programmed. `own`/`inherited`: its effects in the metadata.
     */
    @JvmStatic
    fun pending(cardName: String?, own: Int, inherited: Int, keywords: List<String>): String? {
        val name = cardName?.trim { it <= ' ' }?.lowercase() ?: ""
        val ownDone = BY_NAME.valueOr(name, frozenListOf()).size + CONTINUOUS.valueOr(name, frozenListOf()).size +
                CostModifiers.of(name).size + REPLACEMENTS.valueOr(name, frozenListOf()).size +
                ruleEffects().valueOr(name, frozenListOf()).size
        val inheritedDone = INHERITED.valueOr(name, frozenListOf()).size + INHERITED_REPLACEMENTS.valueOr(name, frozenListOf()).size
        val pending: MutableList<String> = ArrayList()
        if (own > ownDone) pending.add((own - ownDone).toString() + " de " + own + " efectos propios")
        if (inherited > inheritedDone) pending.add((inherited - inheritedDone).toString() + " de " + inherited + " heredados")
        val manual = keywords.map { k -> k.replace(TRAILING_NUMBER, "").trim { it <= ' ' } }
            .filter { k -> !k.isEmpty() && !ENGINE_KEYWORDS.contains(k) }.distinct()
        if (!manual.isEmpty()) pending.add(manual.joinToString(", "))
        return if (pending.isEmpty()) null else pending.joinToString("; ")
    }

    /** What a card gives the creature it is a Recurso of. */
    @JvmStatic
    fun inheritedOf(name: String?): List<EffectScript> {
        return if (name == null) frozenListOf() else INHERITED.valueOr(name.trim { it <= ' ' }.lowercase(), frozenListOf())
    }

    /** A creature's strength while it is a life: its own and what its continuous effects of the lives give it. */
    @JvmStatic
    fun lifeStrength(room: GameRoom, owner: String?, life: CardSlot): Int {
        var strength = if (life.strength == null) 0 else life.strength!!
        for (c in continuousOf(life)) {
            if (Zone.LIFE_STACK.`is`(c.zone) && c.condition!!.test(room, null, life, owner)) strength += c.strength
        }
        // Helena: "reduce … las de las vidas oponentes en N, hasta el final del turno"
        val reduced = EffectEngine.LIFE_REDUCED + life.instanceId + "|"
        for (k in room.usedThisTurn) if (k.startsWith(reduced)) strength -= k.substring(reduced.length).toInt()
        return strength
    }

    /** "En Tributos: esta carta puede considerarse como si estuviera en tu mano para invocaciones especiales." */
    @JvmStatic
    fun specialFromTributes(card: CardSlot): Boolean {
        return traits(card).specialFromTributes
    }

    private val AGRUPAR: Regex = Regex("agrupar\\s*(\\d+)")

    /** "Agrupar N": up to N allies, Letalidad 1 for each (designer ruling 2026-10-31). Agrupar 1 is the one in KEYWORDS. */
    @JvmStatic
    fun agrupar(allies: Int): EffectScript {
        if (allies == 1) return KEYWORDS["agrupar 1"]!!
        return EffectScript("kw:agrupar-$allies", Moment.ON_ATTACK, false, "Agrupar $allies",
            "Si esta carta declara ataque: puedes agotar hasta " + allies + " aliados para que esta carta, durante el ataque, gane sus fuerzas e " +
                    "inflija daño 1 vez extra por cada uno.", frozenListOf<EffectStep>(GroupUp(allies)))
    }

    /** The card's own continuous effects. */
    @JvmStatic
    fun continuousOf(card: CardSlot): List<Continuous> {
        if (card.cardName == null) return frozenListOf()
        return CONTINUOUS.valueOr(card.cardName!!.trim { it <= ' ' }.lowercase(), frozenListOf())
    }

    /** The card's own effects, those its Recursos give it and those of the keywords the engine runs. */
    @JvmStatic
    fun of(card: CardSlot): List<EffectScript> {
        val all: MutableList<EffectScript> = ArrayList(of(card.cardName))
        for (resource in card.resources!!) {
            if (resource.cardName != null) all.addAll(INHERITED.valueOr(resource.cardName!!.trim { it <= ' ' }.lowercase(), frozenListOf()))
        }
        for (keyword in EffectEngine.keywords(card)) {
            var script = KEYWORDS[keyword.lowercase()]
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
        for (scripts in BY_NAME.values) for (s in scripts) if (s.id == id) return s
        for (scripts in INHERITED.values) for (s in scripts) if (s.id == id) return s
        for (s in KEYWORDS.values) if (s.id == id) return s
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
        RESOURCE_TO_BACK_WITH_OTHERS
    }

    /**
     * "cuando esta carta fuera a ser destruida / removida [por el oponente]: en su lugar, puedes …".
     *
     * @param destroyOnly only when it would be destroyed ("removida" also covers being sent to the hand)
     */
    @JvmRecord
    data class Replacement(
        val id: String?, val label: String?, val text: String?, val destroyOnly: Boolean, val byOpponentOnly: Boolean, val limit: Limit?,
        val cost: ReplacementCost?, val resourceFilter: CardFilter?,
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
        return REACTIONS.valueOr(card.cardName!!.trim { it <= ' ' }.lowercase(), frozenListOf())
            .firstOrNull { it is T } as T?
    }

    private fun traits(card: CardSlot): CardTraits {
        return if (card.cardName == null) CardTraits.NONE
        else TRAITS.valueOr(card.cardName!!.trim { it <= ' ' }.lowercase(), CardTraits.NONE)
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

    @JvmStatic
    fun allyRemovalSummon(card: CardSlot): AllyRemovalSummon? {
        return reaction<AllyRemovalSummon>(card)
    }

    /** The card's own replacements and those its Recursos give it. */
    @JvmStatic
    fun replacementsOf(card: CardSlot): List<Replacement> {
        val all: MutableList<Replacement> = ArrayList()
        if (card.cardName != null) all.addAll(REPLACEMENTS.valueOr(card.cardName!!.trim { it <= ' ' }.lowercase(), frozenListOf()))
        for (resource in card.resources!!) {
            if (resource.cardName != null)
                all.addAll(INHERITED_REPLACEMENTS.valueOr(resource.cardName!!.trim { it <= ' ' }.lowercase(), frozenListOf()))
        }
        return all
    }

    /** The Recurso of the card that gives it that replacement (inherited), or null when it is the card's own. */
    @JvmStatic
    fun replacementGiver(card: CardSlot, replacement: Replacement?): CardSlot? {
        for (resource in card.resources!!) {
            if (resource.cardName != null &&
                INHERITED_REPLACEMENTS.valueOr(resource.cardName!!.trim { it <= ' ' }.lowercase(), frozenListOf()).contains(replacement)) return resource
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
        val own = card.cardName!!.trim { it <= ' ' }.lowercase()
        val wanted = name.trim { it <= ' ' }.lowercase()
        return own == wanted || wanted == traits(card).alias
    }

    /** What the files say of a card, to the registries. */
    private fun install(d: CardDefinition) {
        val name = d.name
        if (!d.effects().isEmpty()) BY_NAME[name!!] = d.effects()
        if (!d.inherited().isEmpty()) INHERITED[name!!] = d.inherited()
        if (!d.continuous().isEmpty()) CONTINUOUS[name!!] = d.continuous()
        if (d.traits != null) TRAITS[name!!] = d.traits
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
    }

    init {
        CardSources.cards().forEach(::install)
    }
}
