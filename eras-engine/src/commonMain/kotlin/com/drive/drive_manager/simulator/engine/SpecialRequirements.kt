package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.platform.Platform
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmRecord
import kotlin.math.max
import com.drive.drive_manager.simulator.engine.EffectEngine.allies
import com.drive.drive_manager.simulator.engine.EffectEngine.canPay
import com.drive.drive_manager.simulator.engine.EffectEngine.find
import com.drive.drive_manager.simulator.engine.EffectEngine.hasLevel
import com.drive.drive_manager.simulator.engine.EffectEngine.levels
import com.drive.drive_manager.simulator.engine.EffectEngine.name
import com.drive.drive_manager.simulator.engine.EffectEngine.plural
import com.drive.drive_manager.simulator.engine.EffectEngine.strength
import com.drive.drive_manager.simulator.engine.EffectEngine.summoningAs
import com.drive.drive_manager.simulator.engine.EffectEngine.triggerEvent
import com.drive.drive_manager.simulator.engine.EffectEngine.zone
import com.drive.drive_manager.simulator.engine.EffectEngine.zoneName
import com.drive.drive_manager.simulator.engine.EffectStep.*
import com.drive.drive_manager.simulator.engine.Rituals.addsUpTo
import com.drive.drive_manager.simulator.engine.Rituals.canRitual
import com.drive.drive_manager.simulator.engine.Rituals.shares
import com.drive.drive_manager.simulator.engine.Rituals.sumsExactly
import com.drive.drive_manager.simulator.engine.effects.model.SpecialRule
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState
import com.drive.drive_manager.simulator.room.Zone

/** What a special summon asks for: its Requerimiento, who can summon and with which materials. */
object SpecialRequirements {

    private val COMMA = Platform.regex(",")

    // ------------------------------------------------------------------ Evolución, Ascenso, Materialización (rule 9)

    /**
     * What a non-Ritual special summon needs, as its card file programs it ([RequirementSpec], [fromSpec]).
     *
     * @param level         the summoner's level (the lowest, with `maxLevel`: "criatura nivel 3 o 4")
     * @param maxLevel      the highest summoner level
     * @param atLeast       "nivel N o mayor"
     * @param materialCount Materialización: how many other materials ("+ material nivel 2": 1; "3 criaturas nivel 3": 3; none: 0)
     * @param materialZones where they may come from ("en campo o mano"): "field", "hand", "tributeZone"
     * @param noSummoner    "K criaturas nivel N en tu mano o tributos": no summoner on the field, all of them are materials; it enters the Retaguardia
     * @param summonerZones "invocador nivel N en tu mano o tributos + K criaturas nivel M…": the summoner is declared first, from those
     *                      zones (it becomes the first material); null when there is no such summoner
     * @param alternative   "(A) o (B)": the other way, or null
     * @param alone         "mientras no controles otras criaturas": the summoner is the only creature on the field
     * @param strongest     "con fuerza igual o mayor que la de cualquier otro aliado"
     */
    @JvmRecord
    data class SpecialRequirement(
        val kind: String?, val level: Int, val alliesSum: Int, val materialLevel: Int, val levelSum: Int, val atLeast: Boolean, val maxLevel: Int,
        val materialCount: Int, val materialZones: List<String>?, val noSummoner: Boolean, val alternative: SpecialRequirement?,
        val alone: Boolean, val strongest: Boolean, val summonerZones: List<String>?, val moreMaterials: Boolean,
        /** Ascenso: `alliesSum` counts the allies' costs ("suman al menos 5 en coste"), not their levels. */
        val alliesSumByCost: Boolean = false,
        /** "mientras no controlas otros aliados nivel N": N (0 without it); the summoner may be of that level. */
        val aloneAtLevel: Int = 0,
        /** Enlace: "criatura nivel N en una formación de [al menos] K criaturas": K (0 without it), and whether it is "al menos". */
        val formationSize: Int = 0,
        val formationAtLeast: Boolean = false,
        /** Programmed by hand ([RequirementSpec]): what else the summoner must be ("[dragón] o [fauna]"); null: any creature. */
        val summonerFilter: CardFilter? = null,
        /** Programmed by hand: what else the materials must be ("[artefacto]", a utility); null: creatures. */
        val materialFilter: CardFilter? = null,
        /** Programmed by hand: "material nivel N o menor". */
        val materialOrLess: Boolean = false,
        /** Programmed by hand: any other check on the board ("mientras controlas 2 o más [ninjas]"); null: none. */
        val condition: Condition? = null,
        /** What [condition] asks, for the message; null: the printed text. */
        val conditionText: String? = null,
        /**
         * "+ materiales que sumen N en nivel en tu descarte": with [levelSum], the materials come from [materialZones] and their levels
         * add up to N by themselves (the summoner does not count). False: "aliados que sumen N en nivel", the summoner included.
         */
        val sumInZones: Boolean = false,
        /** With [moreMaterials]: "1 o 2 aliados", at most this many materials; null: no limit. */
        val maxMaterials: Int? = null,
    ) {
        /** The most materials that may be chosen out of `available`. */
        fun materialsAtMost(available: Int): Int = if (maxMaterials == null) available else kotlin.math.min(available, maxMaterials)

        /** `moreMaterials`: "N o más criaturas" as materials. */
        constructor(
            kind: String?, level: Int, alliesSum: Int, materialLevel: Int, levelSum: Int, atLeast: Boolean, maxLevel: Int,
            materialCount: Int, materialZones: List<String>?, noSummoner: Boolean, alternative: SpecialRequirement?,
            alone: Boolean, strongest: Boolean, summonerZones: List<String>?,
        ) : this(kind, level, alliesSum, materialLevel, levelSum, atLeast, maxLevel, materialCount, materialZones, noSummoner, alternative,
                alone, strongest, summonerZones, false)

        constructor(
            kind: String?, level: Int, alliesSum: Int, materialLevel: Int, levelSum: Int, atLeast: Boolean, maxLevel: Int,
            materialCount: Int, materialZones: List<String>?, noSummoner: Boolean, alternative: SpecialRequirement?,
            alone: Boolean, strongest: Boolean,
        ) : this(kind, level, alliesSum, materialLevel, levelSum, atLeast, maxLevel, materialCount, materialZones, noSummoner, alternative,
                alone, strongest, null)

        fun withAlternative(other: SpecialRequirement?): SpecialRequirement {
            return copy(alternative = other)
        }

        /** The summoner's level is the one asked for (any level when it asks none). */
        fun levelInRange(summonerLevel: Int): Boolean {
            return level == ANY_LEVEL || (if (atLeast) summonerLevel >= level else summonerLevel >= level && summonerLevel <= maxLevel)
        }

        /** The creature is what the summoner must be, besides its level (a programmed filter). */
        fun summonerMatches(c: CardSlot): Boolean {
            return summonerFilter == null || summonerFilter.matches(c)
        }

        /**
         * The card can summon: a creature, or what the programmed filter's types allow ("criatura o estructura [treant] nivel 1": a structure
         * of the field can be the summoner of an Evolución too).
         */
        fun summonerType(c: CardSlot): Boolean {
            if ("creature" == c.cardType) return true
            val f = summonerFilter ?: return false
            return f.cardType == c.cardType || f.cardTypes().contains(c.cardType)
        }

        /** The card can be one of the materials: a creature (or what the programmed filter says) of the level asked for. */
        fun materialMatches(c: CardSlot): Boolean {
            val typed = materialFilter != null && (materialFilter.cardType != null || materialFilter.cardTypes().isNotEmpty())
            if (!typed && "creature" != c.cardType) return false
            if (c.getLevel() == null) return false
            val levelOk = materialLevel == ANY_LEVEL || (if (materialOrLess) levels(c).any { l -> l in 1..materialLevel } else hasLevel(c, materialLevel))
            return levelOk && (materialFilter == null || materialFilter.matches(c))
        }

        /** " nivel N [o menor]" of the materials. */
        fun materialLevelText(): String {
            return levelText(materialLevel) + (if (materialOrLess && materialLevel != ANY_LEVEL) " o menor" else "")
        }

        /** The summoner is declared from the hand or the Tributos (not the field). */
        fun declaredSummoner(): Boolean {
            return summonerZones != null
        }

        /** A summoner of that level meets it. */
        fun fits(summonerLevel: Int): Boolean {
            return !noSummoner && levelInRange(summonerLevel)
        }
    }

    /** A Requerimiento level that any level meets ("2 criaturas en mano"). */
    const val ANY_LEVEL = 0

    @JvmStatic
    fun levelMeets(card: CardSlot?, level: Int): Boolean {
        return level == ANY_LEVEL || hasLevel(card, level)
    }

    /** " nivel N", or nothing for any level. */
    @JvmStatic
    fun levelText(level: Int): String {
        return if (level == ANY_LEVEL) "" else " nivel $level"
    }

    /** The marker of a Materialización without a summoner, in the prompt's summoner. */
    const val NO_SUMMONER = "-"

    @JvmStatic
    fun specialRequirement(card: CardSlot?): SpecialRequirement? {
        val kind = if (card!!.specialSummonKind == null) "" else card.specialSummonKind!!.lowercase()
        if (!frozenListOf("evolution", "promotion", "materialization").contains(kind)) return null
        // Always programmed in the card file (its `requirement` section); the printed text is only shown. Without it: not programmed
        val programmed = CardEffects.requirementOf(card) ?: return null
        return if (programmed.ritual == null) fromSpec(kind, programmed) else null
    }

    /** A Requerimiento programmed by hand ([RequirementSpec]) as the engine checks it. */
    @JvmStatic
    fun fromSpec(kind: String?, spec: RequirementSpec): SpecialRequirement {
        val s = spec.summoner
        val m = spec.materials
        val declared = s?.zones?.takeIf { z -> z.isNotEmpty() && z != frozenListOf("field") }
        val noSummoner = s?.none == true || declared != null
        // "nivel N o menor": 1..N; level 0: any level
        val level = if (s == null) ANY_LEVEL else if (s.orLess) 1 else s.level
        val maxLevel = if (s == null) ANY_LEVEL else if (s.orLess) s.level else s.maxLevel ?: s.level
        return SpecialRequirement(kind, level, spec.alliesSum, m?.level ?: ANY_LEVEL, m?.levelSum ?: 0, s?.atLeast ?: false, maxLevel,
                m?.count ?: 0, m?.zones?.takeIf { it.isNotEmpty() } ?: frozenListOf("tributeZone", "field"), noSummoner,
                spec.alternative?.let { fromSpec(kind, it) }, spec.alone, spec.strongest, declared, m?.more ?: false, spec.alliesSumByCost,
                spec.aloneAtLevel, spec.formationSize, spec.formationAtLeast, s?.filter, m?.filter, m?.orLess ?: false, spec.condition,
                spec.conditionText, (m?.levelSum ?: 0) > 0 && !m?.zones.isNullOrEmpty(), m?.maxCount)
    }

    /** What else a programmed Requerimiento checks on the board ([SpecialRequirement.condition]) holds. */
    @JvmStatic
    fun conditionMet(room: GameRoom, player: PlayerState, card: CardSlot, req: SpecialRequirement): Boolean {
        return req.condition == null || req.condition.test(room, null, card, player.userId)
    }

    /** The way of the Requerimiento that summoner meets (the printed one or its alternative), or null. */
    @JvmStatic
    fun optionFor(req: SpecialRequirement?, summoner: CardSlot?): SpecialRequirement? {
        if (summoner == null) return if (req!!.noSummoner) req else null
        for (level in levels(summoner)) if (req!!.fits(level) && req.summonerMatches(summoner)) return req
        if (req!!.alternative != null) for (level in levels(summoner)) if (req.alternative!!.fits(level) && req.alternative.summonerMatches(summoner)) return req.alternative
        return null
    }

    @JvmStatic
    fun kindName(kind: String?): String {
        return when (kind!!) {
            "evolution" -> "Evolución"
            "promotion" -> "Ascenso"
            "materialization" -> "Materialización"
            "any" -> "invocación especial"
            else -> "Ritual"
        }
    }

    /** Creatures of the field that can be the summoner: the level asked for, sharing a type or color. */
    @JvmStatic
    fun specialSummoners(player: PlayerState, card: CardSlot, req: SpecialRequirement?): List<CardSlot> {
        val creatures = allies(player)
        return player.field
                .filter { c ->
                    c.getLevel() != null && shares(c, card) && optionFor(req, c)?.summonerType(c) == true
                            // "mientras no controles otras criaturas"
                            && (!req!!.alone || creatures.all { o -> o === c })
                            // "mientras no controlas otros aliados nivel 1"
                            && (req.aloneAtLevel == 0 || creatures.none { o -> o !== c && o.getLevel() == req.aloneAtLevel })
                            // "con fuerza igual o mayor que la de cualquier otro aliado"
                            && (!req.strongest || creatures.all { o -> o === c || strength(o) <= strength(c) })
                            // Enlace: "en una formación de [al menos] K criaturas"
                            && inFormationAsAsked(player, c, optionFor(req, c)!!)
                }
    }

    /** Enlace: the summoner is in a formation of the size its Requerimiento asks ("de 3 criaturas", "de al menos 3 criaturas"). */
    @JvmStatic
    fun inFormationAsAsked(player: PlayerState, summoner: CardSlot, req: SpecialRequirement): Boolean {
        if (req.formationSize <= 0) return true
        val size = Formations.sizeOf(player, summoner)
        return if (req.formationAtLeast) size >= req.formationSize else size == req.formationSize
    }

    /**
     * Materialización: the other material, a creature of that level in the Tributos or on the field (not the
     * summoner); with a level sum ("aliados que sumen 6"), the other allies whose levels complete it.
     */
    @JvmStatic
    fun specialMaterials(player: PlayerState, card: CardSlot, req: SpecialRequirement, summoner: CardSlot?): List<CardSlot> {
        // A summoner declared from the hand or the Tributos: the others are its materials
        if (req.declaredSummoner() && summoner != null) {
            return specialMaterials(player, card, req, null).filter { c -> c !== summoner }
        }
        val option = optionFor(req, summoner) ?: return frozenListOf()
        if (option.levelSum > 0 && !option.sumInZones) {
            return player.field
                    .filter { c -> c !== summoner && c !== card && "creature" == c.cardType && c.getLevel() != null && shares(c, card)
                            && (option.materialFilter == null || option.materialFilter.matches(c)) }
        }
        val all: MutableList<CardSlot> = ArrayList()
        for (z in option.materialZones!!) all.addAll(zone(player, z))
        return all
                .filter { c ->
                    c !== summoner && c !== card && option.materialMatches(c) && shares(c, card)
                }
    }

    /** The summoner has what else the Requerimiento needs (always, but for Materialización). */
    @JvmStatic
    fun materialsPossible(player: PlayerState, card: CardSlot, req: SpecialRequirement, summoner: CardSlot?): Boolean {
        if ("materialization" != req.kind) return true
        val option = optionFor(req, summoner) ?: return false
        val materials = specialMaterials(player, card, req, summoner)
        if (option.sumInZones) return sumsExactly(materials, option.levelSum)
        return if (option.levelSum > 0) levels(summoner).any { l -> option.levelSum > l && sumsExactly(materials, option.levelSum - l) }
        else materials.size >= option.materialCount
    }

    /**
     * "invocador nivel N en tu mano o tributos": the creatures of those zones that can be it, sharing a type or color, with enough
     * others left to be its materials.
     */
    @JvmStatic
    fun declaredSummoners(player: PlayerState, card: CardSlot, req: SpecialRequirement): List<CardSlot> {
        val all: MutableList<CardSlot> = ArrayList()
        for (z in req.summonerZones!!) all.addAll(zone(player, z))
        return all
                .filter { c ->
                    c !== card && "creature" == c.cardType && c.getLevel() != null && levels(c).any { l -> req.levelInRange(l) }
                            && req.summonerMatches(c) && shares(c, card)
                            && specialMaterials(player, card, req, c).size >= req.materialCount
                }
    }

    /** The summoner declared from the hand or the Tributos, wherever it is now. */
    @JvmStatic
    fun findDeclared(player: PlayerState, req: SpecialRequirement, id: String?): CardSlot? {
        for (z in req.summonerZones!!) {
            val c = find(zone(player, z), id)
            if (c != null) return c
        }
        return null
    }

    /** The materials chosen meet the Requerimiento: one of the level asked for, or those completing the level sum. */
    @JvmStatic
    fun checkMaterials(req: SpecialRequirement, summoner: CardSlot?, materials: List<CardSlot?>): String? {
        if ("materialization" != req.kind) return null
        val option = optionFor(req, summoner) ?: return "Ese invocador no cumple el Requerimiento"
        if (option.sumInZones) {
            val chosen = materials.filterNotNull()
            return if (chosen.size == materials.size && addsUpTo(chosen, option.levelSum)) null
            else "Los niveles de los materiales deben sumar exactamente " + option.levelSum + " (suman " + chosen.sumOf { it.getLevel() ?: 0 } + ")"
        }
        if (option.levelSum > 0) {
            val all: MutableList<CardSlot?> = ArrayList(materials)
            all.add(summoner)
            val sum = all.sumOf { it!!.getLevel()!! }
            @Suppress("UNCHECKED_CAST")
            return if (addsUpTo(all as List<CardSlot>, option.levelSum)) null
            else "Los niveles deben sumar exactamente " + option.levelSum + " con el invocador (suman " + sum + ")"
        }
        return if (materials.size == option.materialCount || (option.moreMaterials && materials.size > option.materialCount
                    && (option.maxMaterials == null || materials.size <= option.maxMaterials))) null
        else "Elige " + option.materialCount + " " + plural(option.materialCount, "material", "materiales")
    }

    /** The choice of the materials of a Materialización. */
    @JvmStatic
    fun materialInstruction(req: SpecialRequirement, card: CardSlot?, summoner: CardSlot?): String {
        val option = optionFor(req, summoner) ?: req
        val zones = zonesText(option.materialZones)
        if (req.declaredSummoner() && summoner != null) {
            return ("Elige " + req.materialCount + " " + plural(req.materialCount, "criatura", "criaturas") + req.materialLevelText() + " de " + zones
                    + ": con " + name(summoner) + " (el invocador) se convierten en materiales de " + name(card) + ", que entra en tu retaguardia.")
        }
        if (summoner == null) {
            return ("Elige " + option.materialCount + " criaturas" + option.materialLevelText() + " de " + zones + ": se convierten en materiales de "
                    + name(card) + ", que entra en tu retaguardia.")
        }
        if (option.sumInZones) {
            return ("Elige los materiales de " + name(card) + " de " + zones + ": sus niveles deben sumar exactamente " + option.levelSum
                    + ". Se convierten en materiales junto a " + name(summoner) + ".")
        }
        return if (option.levelSum > 0)
            ("Elige los aliados que se convierten en materiales de " + name(card) + ": con " + name(summoner) + " (nivel " + summoner.getLevel()
                    + ") sus niveles deben sumar exactamente " + option.levelSum + ".")
        else ("Elige el material: " + option.materialCount + " criatura" + option.materialLevelText() + " de " + zones
                + ". Se convierte en material de " + name(card) + " junto a " + name(summoner) + ".")
    }

    /** "tu mano o tus tributos". */
    @JvmStatic
    fun zonesText(zones: List<String>?): String {
        // Any other zone is read as the Tributos
        return (zones!!.map { z ->
            (Zone.parse(z)?.takeIf { x -> x == Zone.FIELD || x == Zone.HAND } ?: Zone.TRIBUTE_ZONE).ownLabel()
        }).joinToString(" o ")
    }

    /** The card can be special summoned from where it is: the hand, or the Tributos when it says so (Falanx). */
    @JvmStatic
    fun specialSource(player: PlayerState, instanceId: String?): CardSlot? {
        var card = find(player.hand, instanceId)
        if (card != null) return card
        card = find(player.tributeZone, instanceId)
        return if (card != null && CardEffects.specialFromTributes(card)) card else null
    }

    /** Cards a player can special summon now (any kind), from the hand or the Tributos, for the board. */
    @JvmStatic
    fun specialSummonable(room: GameRoom, userId: String?): MutableList<String> {
        val player = room.getPlayer(userId)
        if (player == null || room.status != GameRoom.RoomStatus.IN_PROGRESS || userId!! != room.currentTurnUserId
                || "MAIN" != room.currentPhase || room.isEngineBusy()) return frozenListOf()
        val cards: MutableList<CardSlot> = ArrayList(player.hand)
        player.tributeZone.filter { CardEffects.specialFromTributes(it) }.forEach { cards.add(it) }
        @Suppress("UNCHECKED_CAST")
        return cards.filter { c -> "creature" == c.cardType && canSpecial(room, userId, c) }.map { it.instanceId } as MutableList<String>
    }

    /** Ascenso: "mientras tus aliados suman N o más en nivel" (or "en coste": their printed costs). */
    @JvmStatic
    fun alliesAddUp(player: PlayerState, req: SpecialRequirement): Boolean {
        val total = if (req.alliesSumByCost) allies(player).sumOf { c -> c.cost ?: 0 }
        else allies(player).sumOf { c -> if (c.getLevel() == null) 0 else c.getLevel()!! }
        return total >= req.alliesSum
    }

    /** Whether the card can be summoned now by its special summon (any kind), for the board's legal actions. */
    @JvmStatic
    fun canSpecial(room: GameRoom, userId: String?, card: CardSlot?): Boolean {
        return summoningAs(card) { canSpecialAs(room, userId, card) }
    }

    @JvmStatic
    fun canSpecialAs(room: GameRoom, userId: String?, card: CardSlot?): Boolean {
        if ("ritual".equals(card!!.specialSummonKind, ignoreCase = true)) return canRitual(room, userId, card)
        val player = room.getPlayer(userId)
        val req = specialRequirement(card)
        if (player == null || req == null || card.specialCost == null) return false
        if (!canPay(room, userId, card.specialCost!! - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind, card, null))) return false
        if ("promotion" == req.kind && !alliesAddUp(player, req)) return false
        if (!conditionMet(room, player, card, req)) return false
        if (req.declaredSummoner()) return !declaredSummoners(player, card, req).isEmpty()
        if (req.noSummoner) return specialMaterials(player, card, req, null).size >= req.materialCount
        return specialSummoners(player, card, req).any { s -> materialsPossible(player, card, req, s) && affordableWith(room, player, card, req, s) }
    }

    /**
     * The special cost can be paid with this summoner: a discount "of the summoner" (Duregón's) counts only when that
     * creature is the summoner, so the cost is checked with each one. A Materialización with materials is paid once
     * they are chosen, so it is not checked here.
     */
    @JvmStatic
    fun affordableWith(room: GameRoom, player: PlayerState, card: CardSlot, req: SpecialRequirement, summoner: CardSlot?): Boolean {
        val option = optionFor(req, summoner)
        if ("materialization" == req.kind && (option!!.materialCount > 0 || option.levelSum > 0)) return true
        return canPay(room, player.userId, card.specialCost!!
                - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind, card, summoner))
    }

    /**
     * "Cuando fueras a invocar esta carta, si utilizas al menos N criaturas en tu campo: reduce el coste especial en M" (Prototipo XTRM-1N3):
     * the summoner and the materials that come from the field. Designer ruling 2026-10-31.
     */
    @JvmStatic
    fun fieldPiecesDiscount(player: PlayerState, card: CardSlot, summoner: CardSlot?, materials: List<CardSlot>): Int {
        val d = CardEffects.fieldPiecesDiscount(card) ?: return 0
        val used = (if (summoner != null && player.field.contains(summoner)) 1 else 0) + materials.count { player.field.contains(it) }.toLong()
        return if (used >= d.pieces) d.reduce else 0
    }

    /** That discount, when the field could give the pieces (to tell whether the summon is affordable before they are chosen). */
    @JvmStatic
    fun possibleFieldPiecesDiscount(player: PlayerState, card: CardSlot): Int {
        val d = CardEffects.fieldPiecesDiscount(card)
        return if (d != null && allies(player).size >= d.pieces) d.reduce else 0
    }

    /** Creatures an effect lets be summoned by a special summon of that kind, from that zone. */
    @JvmStatic
    fun specialTargets(room: GameRoom, effect: PendingEffect, special: SpecialRule): List<CardSlot> {
        val player = room.getPlayer(effect.controllerId) ?: return frozenListOf()
        val zones = specialZones(player, special, effect)
        // Java's method reference (special.filter()::matches) read the filter at once
        val filter = special.filter!!
        return zones
                .filter { c ->
                    c !== find(player.field, effect.sourceInstanceId)
                            // "invoca especialmente" (kind "any"): by the method the creature has; Ritual ones are added by SpecialProcedure (Rituals)
                            && (if ("any" == special.kind) c.specialSummonKind != null && !"ritual".equals(c.specialSummonKind, ignoreCase = true)
                            else special.kind!!.equals(c.specialSummonKind, ignoreCase = true)) && c.specialCost != null
                            && filter.matches(c)
                            && summoningAs(c) {
                        val req = specialRequirement(c)
                        if (req == null || !canPay(room, player.userId, max(0, c.specialCost!! - special.reduce
                                        - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind, c, null)))) return@summoningAs false
                        if ("promotion" == req.kind && !alliesAddUp(player, req)) return@summoningAs false
                        if (!conditionMet(room, player, c, req)) return@summoningAs false
                        specialSummoners(player, c, req).any { s ->
                            summonerAllowed(special, s, effect)
                                    && materialsPossible(player, c, req, s)
                        }
                    }
                }
    }

    /** "esta carta puede invocar…" (only this card), "otro aliado…" (not this card) or "1 aliado…" (any). */
    @JvmStatic
    fun summonerAllowed(special: SpecialRule, summoner: CardSlot, effect: PendingEffect): Boolean {
        val self = summoner.instanceId!! == effect.sourceInstanceId
        return when (special.summoner!!) {
            "self" -> self
            "other" -> !self
            // "a un aliado para que invoque": the ally chosen before (any, while it is not chosen yet)
            "subject" -> effect.subjectId == null || summoner.instanceId!! == effect.subjectId
            // "1 aliado nivel N puede invocar…"
            else -> !special.summoner.startsWith("level:")
                    || special.summoner.substring(6).toInt() == summoner.getLevel()
        }
    }

    /** The level of the card whose effect caused this trigger ("reduciendo su coste en el nivel de dicho aliado"). */
    @JvmStatic
    fun triggerSourceLevel(room: GameRoom, effect: PendingEffect): Int {
        val trigger = triggerEvent(room, effect)
        val by = if (trigger == null || trigger.data == null) null else trigger.data!!["byCard"]
        // "dicho aliado": the creature that tributed it as it is now (one that evolved since is the creature it became)
        val source = if (by == null) null else EffectEngine.actingCard(room, by as String)
        return if (source == null || source.getLevel() == null) 0 else source.getLevel()!!
    }

    /** The zones a special summon by effect takes the creature from ("de tu mano o tributos"). */
    @JvmStatic
    fun specialZones(player: PlayerState?, special: SpecialRule): List<CardSlot> {
        return specialZones(player, special, null)
    }

    /** `effect`: whose card's Recursos the zone "resources" is ("invocar especialmente el recurso de este efecto"). */
    @JvmStatic
    fun specialZones(player: PlayerState?, special: SpecialRule, effect: PendingEffect?): List<CardSlot> {
        val all: MutableList<CardSlot> = ArrayList()
        for (z in JavaText.split(COMMA, special.zone!!)) {
            val name = z.trim { it <= ' ' }
            if (name == "resources") {
                val host = if (effect == null) null else find(player!!.field, effect.sourceInstanceId)
                if (host != null) all.addAll(host.resources!!)
                continue
            }
            all.addAll(zone(player, name))
        }
        return all
    }

    @JvmStatic
    fun zonesName(special: SpecialRule): String {
        return (JavaText.split(COMMA, special.zone!!).map { z -> zoneName(z.trim { it <= ' ' }) }).joinToString(" o ")
    }
}
