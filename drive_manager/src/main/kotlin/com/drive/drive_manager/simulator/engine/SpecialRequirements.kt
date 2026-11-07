package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.engine.EffectEngine.allies
import com.drive.drive_manager.simulator.engine.EffectEngine.canPay
import com.drive.drive_manager.simulator.engine.EffectEngine.find
import com.drive.drive_manager.simulator.engine.EffectEngine.findAnywhere
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
import java.util.Locale
import java.util.regex.Pattern

/** What a special summon asks for: its Requerimiento, who can summon and with which materials. */
object SpecialRequirements {

    private val COMMA = Pattern.compile(",")

    // ------------------------------------------------------------------ Evolución, Ascenso, Materialización (rule 9)

    /**
     * What a non-Ritual special summon needs: the summoner's level, for Ascenso the level the allies must add up
     * to, for Materialización the level of the other material (from the Tributos or the field).
     */
    /**
     * A special summon's Requerimiento as printed.
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
    ) {
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
            return SpecialRequirement(kind, level, alliesSum, materialLevel, levelSum, atLeast, maxLevel, materialCount, materialZones,
                    noSummoner, other, alone, strongest, summonerZones, moreMaterials)
        }

        /** The summoner is declared from the hand or the Tributos (not the field). */
        fun declaredSummoner(): Boolean {
            return summonerZones != null
        }

        /** A summoner of that level meets it. */
        fun fits(summonerLevel: Int): Boolean {
            return !noSummoner && (if (atLeast) summonerLevel >= level else summonerLevel >= level && summonerLevel <= maxLevel)
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
        val kind = if (card!!.specialSummonKind == null) "" else card.specialSummonKind!!.lowercase(Locale.ROOT)
        if (!java.util.List.of("evolution", "promotion", "materialization").contains(kind) || card.requirement == null) return null
        val text = card.requirement!!.lowercase(Locale.ROOT).trim { it <= ' ' }
        // "(criatura nivel 3) o (criatura nivel 2 + material nivel 2 en tu mano o campo)"
        val either = Pattern.compile("^\\((.*)\\)\\s*o\\s*\\((.*)\\)\\.?$").matcher(text)
        if (either.find()) {
            val first = parseRequirement(kind, either.group(1))
            val second = parseRequirement(kind, either.group(2))
            return if (first == null) second else first.withAlternative(second)
        }
        return parseRequirement(kind, text)
    }

    @JvmStatic
    fun parseRequirement(kind: String?, text: String): SpecialRequirement? {
        val zones = materialZones(text)
        val materialization = "materialization" == kind
        // "criatura nivel 3 en mano + 2 criaturas nivel 3 en tu mano o tributos": the summoner is declared from where it says (not
        // the field) and enters with the others as its materials (Roto-G0)
        // "criatura nivel 3 en mano o campo (invocador) + 1 o más criaturas nivel 3 en mano o campo" (Prototipo XTRM-1N3)
        val declared = Pattern.compile("^(?:invocador|criatura)\\s+ni\\w*\\s*(\\d+)\\s+(en\\s+[^+]*)\\+\\s*(\\d+)(\\s*o\\s*m[aá]s)?\\s*criaturas?\\s*ni\\w*\\s*(\\d+)(.*)$")
                .matcher(text)
        if (materialization && declared.find()) {
            val summonerZones = materialZones(declared.group(2))
            val rest = if (declared.group(6).all { Character.isWhitespace(it) }) summonerZones else materialZones(declared.group(6))
            val level = Integer.parseInt(declared.group(1))
            return SpecialRequirement(kind, level, 0, Integer.parseInt(declared.group(5)), 0, false, level, Integer.parseInt(declared.group(3)),
                    rest, true, null, false, false, summonerZones, declared.group(4) != null)
        }
        // "2 criaturas en mano" (Unidad de Seguridad A-5H): one is the summoner declared from there, the rest its materials, of any level
        val anyLevel = Pattern.compile("^(\\d+)\\s*criaturas\\s+(en\\s+[^+]*?)\\.?$").matcher(text)
        if (materialization && anyLevel.find()) {
            val from = materialZones(anyLevel.group(2))
            return SpecialRequirement(kind, ANY_LEVEL, 0, ANY_LEVEL, 0, false, ANY_LEVEL, Integer.parseInt(anyLevel.group(1)) - 1, from, true, null,
                    false, false, from)
        }
        // "2 criaturas nivel 1 en tu mano o tributos": no summoner
        val many = Pattern.compile("(\\d+)\\s*criaturas\\s*ni\\w*\\s*(\\d+)").matcher(text)
        if (materialization && !text.contains("+") && many.find()) {
            return SpecialRequirement(kind, 0, 0, Integer.parseInt(many.group(2)), 0, false, 0, Integer.parseInt(many.group(1)), zones, true, null,
                    false, false)
        }
        val level = Pattern.compile("ni[ev]*l\\s*(\\d+)(?:\\s*o\\s*(\\d+))?").matcher(text)
        if (!level.find()) return null
        val summonerLevel = Integer.parseInt(level.group(1))
        val maxLevel = if (level.group(2) != null) Integer.parseInt(level.group(2)) else summonerLevel
        val sum = Pattern.compile("suman\\s*(\\d+)").matcher(text)
        val alliesSum = if ("promotion" == kind && sum.find()) Integer.parseInt(sum.group(1)) else 0
        var materialLevel = summonerLevel
        var levelSum = 0
        var materialCount = 0
        if (materialization) {
            // "+ material nivel 2" (also with a misprint: "material novel 4", "material nivel en tributos")
            val material = Pattern.compile("\\+\\s*material\\s*\\w*\\s*(\\d+)?").matcher(text)
            if (material.find()) {
                materialCount = 1
                if (material.group(1) != null) materialLevel = Integer.parseInt(material.group(1))
            }
            // "aliados que sumen 6 en nivel, incluido el invocador nivel 3 o mayor"
            val allies = Pattern.compile("sumen\\s*(\\d+)").matcher(text)
            if (allies.find()) levelSum = Integer.parseInt(allies.group(1))
        }
        val atLeast = Pattern.compile("ni[ev]*l\\s*\\d+\\s*o\\s*mayor").matcher(text).find()
        val alone = Pattern.compile("no\\s+control\\w*\\s+otras\\s+criaturas").matcher(text).find()
        val strongest = Pattern.compile("fuerza\\s+igual\\s+o\\s+mayor\\s+que\\s+la\\s+de\\s+cualquier\\s+otro\\s+aliado").matcher(text).find()
        return SpecialRequirement(kind, summonerLevel, alliesSum, materialLevel, levelSum, atLeast, maxLevel, materialCount, zones, false, null,
                alone, strongest)
    }

    /** "en campo o mano", "en tu mano o tributos", "en el campo"; when it says none, the Tributos or the field. */
    @JvmStatic
    fun materialZones(text: String): List<String> {
        val m = Pattern.compile("(?:en|de)\\s+(?:tu\\s+|tus\\s+|el\\s+)?(campo|mano|tributos|descarte)(?:\\s*o\\s+(?:tu\\s+|tus\\s+|el\\s+)?(campo|mano|tributos|descarte))?")
                .matcher(text)
        if (!m.find()) return java.util.List.of("tributeZone", "field")
        val zones: MutableList<String> = ArrayList()
        for (g in 1..2) {
            if (m.group(g) == null) continue
            zones.add(when (m.group(g)) {
                "campo" -> "field"
                "mano" -> "hand"
                "descarte" -> "discardPile"
                else -> "tributeZone"
            })
        }
        return zones
    }

    /** The way of the Requerimiento that summoner meets (the printed one or its alternative), or null. */
    @JvmStatic
    fun optionFor(req: SpecialRequirement?, summoner: CardSlot?): SpecialRequirement? {
        if (summoner == null) return if (req!!.noSummoner) req else null
        for (level in levels(summoner)) if (req!!.fits(level)) return req
        if (req!!.alternative != null) for (level in levels(summoner)) if (req.alternative!!.fits(level)) return req.alternative
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
                    "creature" == c.cardType && c.getLevel() != null && shares(c, card) && optionFor(req, c) != null
                            // "mientras no controles otras criaturas"
                            && (!req!!.alone || creatures.all { o -> o === c })
                            // "con fuerza igual o mayor que la de cualquier otro aliado"
                            && (!req.strongest || creatures.all { o -> o === c || strength(o) <= strength(c) })
                }
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
        val option = optionFor(req, summoner) ?: return java.util.List.of()
        if (option.levelSum > 0) {
            return player.field
                    .filter { c -> c !== summoner && c !== card && "creature" == c.cardType && c.getLevel() != null && shares(c, card) }
        }
        val all: MutableList<CardSlot> = ArrayList()
        for (z in option.materialZones!!) all.addAll(zone(player, z))
        return all
                .filter { c ->
                    c !== summoner && c !== card && "creature" == c.cardType && c.getLevel() != null
                            && levelMeets(c, option.materialLevel) && shares(c, card)
                }
    }

    /** The summoner has what else the Requerimiento needs (always, but for Materialización). */
    @JvmStatic
    fun materialsPossible(player: PlayerState, card: CardSlot, req: SpecialRequirement, summoner: CardSlot?): Boolean {
        if ("materialization" != req.kind) return true
        val option = optionFor(req, summoner) ?: return false
        val materials = specialMaterials(player, card, req, summoner)
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
                    c !== card && "creature" == c.cardType && c.getLevel() != null && levelMeets(c, req.level) && shares(c, card)
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
        if (option.levelSum > 0) {
            val all: MutableList<CardSlot?> = ArrayList(materials)
            all.add(summoner)
            val sum = all.sumOf { it!!.getLevel()!! }
            @Suppress("UNCHECKED_CAST")
            return if (addsUpTo(all as List<CardSlot>, option.levelSum)) null
            else "Los niveles deben sumar exactamente " + option.levelSum + " con el invocador (suman " + sum + ")"
        }
        return if (materials.size == option.materialCount) null
        else "Elige " + option.materialCount + " " + plural(option.materialCount, "material", "materiales")
    }

    /** The choice of the materials of a Materialización. */
    @JvmStatic
    fun materialInstruction(req: SpecialRequirement, card: CardSlot?, summoner: CardSlot?): String {
        val option = optionFor(req, summoner) ?: req
        val zones = zonesText(option.materialZones)
        if (req.declaredSummoner() && summoner != null) {
            return ("Elige " + req.materialCount + " " + plural(req.materialCount, "criatura", "criaturas") + levelText(req.materialLevel) + " de " + zones
                    + ": con " + name(summoner) + " (el invocador) se convierten en materiales de " + name(card) + ", que entra en tu retaguardia.")
        }
        if (summoner == null) {
            return ("Elige " + option.materialCount + " criaturas" + levelText(option.materialLevel) + " de " + zones + ": se convierten en materiales de "
                    + name(card) + ", que entra en tu retaguardia.")
        }
        return if (option.levelSum > 0)
            ("Elige los aliados que se convierten en materiales de " + name(card) + ": con " + name(summoner) + " (nivel " + summoner.getLevel()
                    + ") sus niveles deben sumar exactamente " + option.levelSum + ".")
        else ("Elige el material: " + option.materialCount + " criatura" + levelText(option.materialLevel) + " de " + zones
                + ". Se convierte en material de " + name(card) + " junto a " + name(summoner) + ".")
    }

    /** "tu mano o tus tributos". */
    @JvmStatic
    fun zonesText(zones: List<String>?): String {
        // Any other zone is read as the Tributos
        return java.lang.String.join(" o ", zones!!.map { z ->
            Zone.parse(z).filter { x -> x == Zone.FIELD || x == Zone.HAND }.orElse(Zone.TRIBUTE_ZONE)!!.ownLabel()
        })
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
                || "MAIN" != room.currentPhase || room.isEngineBusy()) return java.util.List.of()
        val cards: MutableList<CardSlot> = ArrayList(player.hand)
        player.tributeZone.filter { CardEffects.specialFromTributes(it) }.forEach { cards.add(it) }
        @Suppress("UNCHECKED_CAST")
        return cards.filter { c -> "creature" == c.cardType && canSpecial(room, userId, c) }.map { it.instanceId } as MutableList<String>
    }

    /** Ascenso: "mientras tus aliados suman N o más en nivel". */
    @JvmStatic
    fun alliesAddUp(player: PlayerState, req: SpecialRequirement): Boolean {
        return allies(player).sumOf { c -> if (c.getLevel() == null) 0 else c.getLevel()!! } >= req.alliesSum
    }

    /**
     * "mientras controlas al menos N criaturas" in a Requerimiento (Explorador Temerario: "criatura nivel 1, mientras
     * controlas al menos 2 criaturas"): how many creatures you must control, the summoner among them; 0 without it.
     */
    @JvmStatic
    fun creaturesNeeded(card: CardSlot): Int {
        if (card.requirement == null) return 0
        val m = Pattern.compile("controlas\\s+al\\s+menos\\s+(\\d+)\\s+criaturas?").matcher(card.requirement!!.lowercase(Locale.ROOT))
        return if (m.find()) Integer.parseInt(m.group(1)) else 0
    }

    @JvmStatic
    fun enoughCreatures(player: PlayerState, card: CardSlot): Boolean {
        return player.field.count { c -> "creature" == c.cardType }.toLong() >= creaturesNeeded(card)
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
        if (!enoughCreatures(player, card)) return false
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
        val player = room.getPlayer(effect.controllerId) ?: return java.util.List.of()
        val zones = specialZones(player, special)
        // Java's method reference (special.filter()::matches) read the filter at once
        val filter = special.filter!!
        return zones
                .filter { c ->
                    c !== find(player.field, effect.sourceInstanceId)
                            // "invoca especialmente" (kind "any"): by the method the creature has, other than Ritual (its own rules)
                            && (if ("any" == special.kind) c.specialSummonKind != null && !"ritual".equals(c.specialSummonKind, ignoreCase = true)
                            else special.kind!!.equals(c.specialSummonKind, ignoreCase = true)) && c.specialCost != null
                            && filter.matches(c)
                            && summoningAs(c) {
                        val req = specialRequirement(c)
                        if (req == null || !canPay(room, player.userId, Math.max(0, c.specialCost!! - special.reduce
                                        - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind, c, null)))) return@summoningAs false
                        if ("promotion" == req.kind && !alliesAddUp(player, req)) return@summoningAs false
                        if (!enoughCreatures(player, c)) return@summoningAs false
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
                    || Integer.valueOf(special.summoner.substring(6)) == summoner.getLevel()
        }
    }

    /** The level of the card whose effect caused this trigger ("reduciendo su coste en el nivel de dicho aliado"). */
    @JvmStatic
    fun triggerSourceLevel(room: GameRoom, effect: PendingEffect): Int {
        val trigger = triggerEvent(room, effect)
        val by = if (trigger == null || trigger.data == null) null else trigger.data!!["byCard"]
        val source = if (by == null) null else findAnywhere(room, trigger!!.playerId, by as String)
        return if (source == null || source.getLevel() == null) 0 else source.getLevel()!!
    }

    /** The zones a special summon by effect takes the creature from ("de tu mano o tributos"). */
    @JvmStatic
    fun specialZones(player: PlayerState?, special: SpecialRule): List<CardSlot> {
        val all: MutableList<CardSlot> = ArrayList()
        for (z in COMMA.split(special.zone!!)) all.addAll(zone(player, z.trim { it <= ' ' }))
        return all
    }

    @JvmStatic
    fun zonesName(special: SpecialRule): String {
        return java.lang.String.join(" o ", COMMA.split(special.zone!!).map { z -> zoneName(z.trim { it <= ' ' }) })
    }
}
