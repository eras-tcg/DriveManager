package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.platform.Platform
import kotlin.jvm.JvmStatic
import com.drive.drive_manager.simulator.engine.effects.model.Selector
import com.drive.drive_manager.simulator.engine.effects.model.SpecialRule
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState

/** What each choice of a special summon by effect offers, under its rule. The choices themselves are plain Select actions. */
object SpecialProcedure {

    private val COMMA = Platform.regex(",")

    /** The rule as it is now: what depends on the game, worked out. */
    @JvmStatic
    fun resolve(room: GameRoom, effect: PendingEffect, s: SpecialRule?): SpecialRule {
        val ctx = EngineContext(room, effect)
        val reduce = if (s!!.reduceBy == null) s.reduce else ctx.amount(s.reduceBy)
        var kind = s.kind
        var filter = s.filter
        if (SpecialRule.OF_THIS_CARD == kind) {
            val self = EffectEngine.findAnywhere(room, effect.controllerId, effect.sourceInstanceId)
            kind = if (self == null || self.specialSummonKind == null) "any" else self.specialSummonKind
            filter = CardFilter.any().named(effect.sourceName)
        }
        if (s.relative != null) {
            val other = ctx.relativeTo(s.relative.to)
            filter = if (other == null || other.getLevel() == null) filter!!.withLevel(-1) else filter!!.withLevel(other.getLevel()!!).notNamed(other.cardName)
        }
        return SpecialRule(kind, s.zone, reduce, s.summoner, filter)
    }

    private fun target(room: GameRoom, effect: PendingEffect, rule: SpecialRule): CardSlot? {
        val player = room.getPlayer(effect.controllerId)
        return if (effect.ritualTargetId == null) null else EffectEngine.find(SpecialRequirements.specialZones(player, rule), effect.ritualTargetId)
    }

    /** "el aliado elegido", "esta carta", or the one chosen in the summoner's choice. */
    @JvmStatic
    fun summoner(room: GameRoom, effect: PendingEffect, rule: SpecialRule): CardSlot? {
        val player = room.getPlayer(effect.controllerId)
        return when (rule.summoner!!) {
            "subject" -> StepHelpers.subject(room, effect)
            "self" -> EffectEngine.find(player!!.field, effect.sourceInstanceId)
            else -> EffectEngine.find(player!!.field, effect.ritualSummonerId)
        }
    }

    /**
     * The summon goes by Ritual: the rule says so, or "invoca especialmente" (kind any) and the creature chosen is summoned by Ritual (designer
     * 2026-11-13: it only enables that creature's own special summon, whatever its method).
     */
    private fun ritual(room: GameRoom, effect: PendingEffect, special: Selector.Special): Boolean {
        val kind = special.rule!!.kind
        if (SpecialRule.RITUAL == kind) return true
        if ("any" != kind) return false
        val target = target(room, effect, resolve(room, effect, special.rule))
        return target != null && SpecialRule.RITUAL.equals(target.specialSummonKind, ignoreCase = true)
    }

    private fun asRitual(rule: SpecialRule): SpecialRule = SpecialRule(SpecialRule.RITUAL, rule.zone, rule.reduce, rule.summoner, rule.filter)

    @JvmStatic
    fun options(room: GameRoom, effect: PendingEffect, special: Selector.Special?): List<CardSlot> {
        val rule = resolve(room, effect, special!!.rule)
        val player = room.getPlayer(effect.controllerId)
        if (ritual(room, effect, special)) return ritualOptions(room, effect, rule, special.stage, player)
        if (special.stage == Selector.Stage.CARD) {
            if (effect.ritualTargetId != null) return frozenListOf()
            val targets = SpecialRequirements.specialTargets(room, effect, rule)
            // "invoca especialmente": the creatures summoned by Ritual too
            return if ("any" == rule.kind) targets + Rituals.ritualTargets(room, effect, asRitual(rule)) else targets
        }
        val target = target(room, effect, rule) ?: return frozenListOf()
        val req = SpecialRequirements.specialRequirement(target)
        val summoner = summoner(room, effect, rule)
        if (special.stage == Selector.Stage.SUMMONER) {
            if (summoner != null) return frozenListOf()
            return SpecialRequirements.specialSummoners(player!!, target, req!!)
                    .filter { s -> SpecialRequirements.summonerAllowed(rule, s, effect) && SpecialRequirements.materialsPossible(player, target, req, s) }
        }
        if (summoner == null || "materialization" != req!!.kind) return frozenListOf()
        return SpecialRequirements.specialMaterials(player!!, target, req, summoner)
    }

    private fun ritualOptions(room: GameRoom, effect: PendingEffect, rule: SpecialRule, stage: Selector.Stage?, player: PlayerState?): List<CardSlot> {
        if (stage == Selector.Stage.CARD) return if (effect.ritualTargetId != null) frozenListOf() else Rituals.ritualTargets(room, effect, rule)
        val target = target(room, effect, rule) ?: return frozenListOf()
        val req = Rituals.requirement(target)
        val materials = Rituals.ritualMaterials(player!!, target)
        val summoner = summoner(room, effect, rule)
        if (req != null && req.parts.isNotEmpty()) {
            if (stage == Selector.Stage.SUMMONER) return if (summoner != null) frozenListOf() else RitualParts.summoners(room, player, target, req.parts)
            return if (summoner == null) frozenListOf() else RitualParts.others(room, player, target, req.parts, summoner)
        }
        if (stage == Selector.Stage.SUMMONER) {
            // Rule 9.2.0.13: the summoner is declared first; it must meet part of the Requerimiento
            if (summoner != null) return frozenListOf()
            return if (req!!.sum) Rituals.summonerCandidates(materials, req.level) else materials.filter { req.fits(it) }
        }
        if (summoner == null || !req!!.sum || EffectEngine.hasLevel(summoner, req.level)) return frozenListOf()
        return Rituals.fittingSacrifices(materials.filter { c -> c !== summoner }, remaining(player, target, req, summoner))
    }

    /** What the other creatures of a Ritual must add up to, with that summoner. */
    private fun remaining(player: PlayerState?, target: CardSlot?, req: Rituals.Requirement?, summoner: CardSlot?): Int {
        return req!!.level - Rituals.summonerLevel(summoner, Rituals.ritualMaterials(player!!, target!!).filter { c -> c !== summoner }, req.level)
    }

    @JvmStatic
    fun shown(room: GameRoom, effect: PendingEffect, special: Selector.Special?): List<CardSlot> {
        val player = room.getPlayer(effect.controllerId)
        if (ritual(room, effect, special!!) && special.stage != Selector.Stage.CARD) {
            val target = target(room, effect, resolve(room, effect, special.rule))
            return if (target == null) player!!.field else player!!.field + RitualParts.enemies(room, player, target)
        }
        return when (special.stage!!) {
            Selector.Stage.CARD -> SpecialRequirements.specialZones(player, special.rule!!)
            Selector.Stage.SUMMONER -> player!!.field
            Selector.Stage.MATERIAL -> {
                val all: MutableList<CardSlot> = ArrayList(player!!.tributeZone)
                all.addAll(player.field)
                all
            }
        }
    }

    /** {min, max, most levels they may add up to (0: no limit)} of the choice. */
    @JvmStatic
    fun bounds(room: GameRoom, effect: PendingEffect, special: Selector.Special?, available: Int): IntArray {
        if (ritual(room, effect, special!!)) {
            if (special.stage != Selector.Stage.MATERIAL) return intArrayOf(1, 1, 0)
            val rule = resolve(room, effect, special.rule)
            val target = target(room, effect, rule)
            val player = room.getPlayer(effect.controllerId)
            val parts = Rituals.requirement(target)?.parts ?: frozenListOf()
            if (parts.isNotEmpty()) return intArrayOf(parts.size - 1, parts.size - 1, 0)
            return intArrayOf(1, available, remaining(player, target, Rituals.requirement(target), summoner(room, effect, rule)))
        }
        return when (special.stage!!) {
            Selector.Stage.CARD -> intArrayOf(0, 1, 0)
            Selector.Stage.SUMMONER -> intArrayOf(1, 1, 0)
            Selector.Stage.MATERIAL -> {
                val rule = resolve(room, effect, special.rule)
                if (SpecialRequirements.specialRequirement(target(room, effect, rule))!!.levelSum > 0) intArrayOf(1, available, 0) else intArrayOf(1, 1, 0)
            }
        }
    }

    @JvmStatic
    fun instruction(room: GameRoom, effect: PendingEffect, special: Selector.Special?): String {
        val rule = resolve(room, effect, special!!.rule)
        if (ritual(room, effect, special)) return ritualInstruction(room, effect, rule, special.stage)
        if (special.stage == Selector.Stage.CARD) {
            return ("Puedes elegir 1 criatura de tu " + SpecialRequirements.zonesName(rule) + " para invocarla por " + SpecialRequirements.kindName(rule.kind)
                    + (if (rule.reduce > 0) " reduciendo su coste especial en " + rule.reduce else "") + ", o ninguna.")
        }
        val target = target(room, effect, rule)
        val req = SpecialRequirements.specialRequirement(target)
        if (special.stage == Selector.Stage.SUMMONER) {
            return "Elige el invocador: 1 criatura nivel " + req!!.level + (if (req.atLeast) " o mayor" else "") + ". " + EffectEngine.name(target) + " ocupa su lugar y hereda su estado."
        }
        return SpecialRequirements.materialInstruction(req!!, target, summoner(room, effect, rule))
    }

    private fun ritualInstruction(room: GameRoom, effect: PendingEffect, rule: SpecialRule, stage: Selector.Stage?): String {
        if (stage == Selector.Stage.CARD) {
            return "Elige 1 criatura de tus " + SpecialRequirements.zonesName(rule) + " para invocarla por Ritual pagando su coste especial reducido en " + rule.reduce + "."
        }
        val target = target(room, effect, rule)
        val req = Rituals.requirement(target)
        if (req != null && req.parts.isNotEmpty()) {
            val summoner = summoner(room, effect, rule)
            return if (stage == Selector.Stage.SUMMONER || summoner == null) RitualParts.summonerInstruction(req.parts, target!!)
            else RitualParts.instruction(req.parts, summoner)
        }
        if (stage == Selector.Stage.SUMMONER) {
            return ("Elige el invocador" + (if (req!!.sum) "" else ": 1 criatura nivel " + req.level + (if (req.atLeast) " o mayor" else ""))
                    + ". Se sacrifica y " + EffectEngine.name(target) + " ocupa su lugar y hereda su estado.")
        }
        val summoner = summoner(room, effect, rule)
        return ("Elige las demás criaturas que se sacrifican junto a " + EffectEngine.name(summoner) + " (el invocador): sus niveles deben sumar exactamente "
                + remaining(room.getPlayer(effect.controllerId), target, req, summoner) + ".")
    }

    /** Whether the materials picked meet the Requerimiento: an error, or null. */
    @JvmStatic
    fun checkMaterials(room: GameRoom, effect: PendingEffect, special: Selector.Special?, picks: List<String?>?): String? {
        if (special!!.stage != Selector.Stage.MATERIAL) return null
        val rule = resolve(room, effect, special.rule)
        val target = target(room, effect, rule)
        val summoner = summoner(room, effect, rule)
        if (ritual(room, effect, special)) {
            val req = Rituals.requirement(target)
            if (req != null && req.parts.isNotEmpty()) {
                val player = room.getPlayer(effect.controllerId)!!
                val others = picks!!.mapNotNull { id -> EngineContext(room, effect).find(id) }
                return RitualParts.check(room, player, target!!, req.parts, summoner!!, others)
            }
            val total = summoner!!.getLevel()!! + picks!!.map { id -> EngineContext(room, effect).find(id) }.sumOf { it!!.getLevel()!! }
            return if (req!!.sum && total != req.level) "Los niveles suman " + total + ": deben sumar exactamente " + req.level else null
        }
        val materials = picks!!.map { id -> EngineContext(room, effect).find(id) }
        return SpecialRequirements.checkMaterials(SpecialRequirements.specialRequirement(target)!!, summoner, materials)
    }

    /** The summon, with what was chosen. Nothing when no creature was chosen. */
    @JvmStatic
    fun summon(room: GameRoom, effect: PendingEffect, raw: SpecialRule?) {
        val rule = resolve(room, effect, raw)
        val player = room.getPlayer(effect.controllerId)
        val target = target(room, effect, rule)
        if (target == null) {
            effect.lastDone = false
            return
        }
        val summoner = summoner(room, effect, rule)
        val materials: MutableList<CardSlot> = ArrayList()
        for (id in effect.saved.valueOr(EngineContext.SPECIAL_MATERIALS, frozenListOf())) {
            val material = EngineContext(room, effect).find(id)
            if (material != null) materials.add(material)
        }
        effect.saved.remove(EngineContext.SPECIAL_MATERIALS)
        if (SpecialRule.RITUAL == rule.kind || ("any" == rule.kind && SpecialRule.RITUAL.equals(target.specialSummonKind, ignoreCase = true))) {
            Summons.performRitual(room, effect, player!!, target, asRitual(rule), summoner, materials)
            effect.ritualSummonerId = null
            return
        }
        val req = SpecialRequirements.specialRequirement(target)
        val from = if (EffectEngine.find(player!!.hand, target.instanceId) != null) "hand"
        else if (EffectEngine.find(player.tributeZone, target.instanceId) != null) "tributeZone" else JavaText.split(COMMA, rule.zone!!)[0].trim { it <= ' ' }
        effect.ritualTargetId = null
        effect.ritualSummonerId = null
        SpecialSummons.specialSummon(room, player, target, req!!, summoner, materials, from, rule.reduce, effect)
        effect.summonedId = target.instanceId
        effect.lastDone = true
    }
}
