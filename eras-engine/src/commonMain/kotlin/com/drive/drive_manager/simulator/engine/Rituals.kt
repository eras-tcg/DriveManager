package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.platform.Platform
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmRecord
import kotlin.math.max
import com.drive.drive_manager.simulator.engine.Chain.advance
import com.drive.drive_manager.simulator.engine.EffectEngine.canPay
import com.drive.drive_manager.simulator.engine.EffectEngine.find
import com.drive.drive_manager.simulator.engine.EffectEngine.gemsOf
import com.drive.drive_manager.simulator.engine.EffectEngine.gemsText
import com.drive.drive_manager.simulator.engine.EffectEngine.hasLevel
import com.drive.drive_manager.simulator.engine.EffectEngine.levels
import com.drive.drive_manager.simulator.engine.EffectEngine.name
import com.drive.drive_manager.simulator.engine.EffectEngine.pay
import com.drive.drive_manager.simulator.engine.EffectEngine.rulesPrompt
import com.drive.drive_manager.simulator.engine.EffectEngine.strength
import com.drive.drive_manager.simulator.engine.EffectEngine.summoningAs
import com.drive.drive_manager.simulator.engine.EffectEngine.zone
import com.drive.drive_manager.simulator.engine.EffectStep.*
import com.drive.drive_manager.simulator.engine.Summons.askTap
import com.drive.drive_manager.simulator.engine.Summons.checkMainPhase
import com.drive.drive_manager.simulator.engine.Summons.ritualSummon
import com.drive.drive_manager.simulator.engine.Summons.starterAdvantage
import com.drive.drive_manager.simulator.engine.effects.model.SpecialRule
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState

/** Rituals, from the hand or by an effect. */
object Rituals {

    // ------------------------------------------------------------------ Ritual from the hand (rule 9.3)

    /**
     * Ritual summon from the hand (rule 9.3): pays the special cost and uses creatures that meet the
     * Requerimiento and share a type or color with it; the summoner is sacrificed and the creature takes its
     * place. `summonerId` is the creature it was dropped onto, if any; whatever is still missing is asked
     * with a prompt, and nothing is paid until it is done.
     */
    @JvmStatic
    fun ritualByPlayer(room: GameRoom, userId: String?, instanceId: String?, summonerId: String?): String? {
        val who = room.getPlayer(userId)
        return summoningAs(if (who == null) null else find(who.hand, instanceId)) { ritualByPlayerAs(room, userId, instanceId, summonerId) }
    }

    @JvmStatic
    fun ritualByPlayerAs(room: GameRoom, userId: String?, instanceId: String?, summonerId: String?): String? {
        val error = checkMainPhase(room, userId, "invocar")
        if (error != null) return error
        val player = room.getPlayer(userId)
        val card = (if (player == null) null else find(player.hand, instanceId)) ?: return "La carta no está en tu mano"
        player!!
        val req = requirement(card)
        if (!"ritual".equals(card.specialSummonKind, ignoreCase = true) || req == null || card.specialCost == null) {
            return if ("ritual".equals(card.specialSummonKind, ignoreCase = true) && card.specialCost != null)
                name(card) + " no tiene su Requerimiento de Ritual programado" else name(card) + " no se invoca por Ritual"
        }
        if (askTap(room, player, card, "ritual", summonerId)) return null
        val cost = ritualCost(room, userId, card)
        if (!canPay(room, userId, cost)) return "No tienes gemas suficientes: tienes " + gemsOf(room, userId) + " y el coste especial es " + cost
        val materials = ritualMaterials(player, card)
        val missing = "Tus criaturas no cumplen el Requerimiento de " + name(card) + ": " + (card.requirement ?: "").trim { it <= ' ' }
        if (req.parts.isNotEmpty()) return partsByPlayer(room, player, card, req.parts, summonerId, missing)

        if (summonerId != null) {
            val summoner = find(player.field, summonerId) ?: return "El invocador debe ser una criatura de tu campo"
            if (!materials.contains(summoner)) return name(summoner) + " no comparte tipo ni color con " + name(card)
            if (!req.sum) {
                if (!req.fits(summoner)) return missing
                // "+ 1 o más aliados": the others sacrificed with it are chosen next
                if (req.extra > 0) {
                    val others = materials.filter { c -> c !== summoner }
                    if (others.size < req.extra) return missing
                    ritualPrompt(room, player, card, summoner, others, "Elige los demás aliados que se sacrifican junto a " + name(summoner)
                            + ": " + req.extra + " o más.", others.size)
                    room.prompt!!.min = req.extra
                    return null
                }
                handRitual(room, player, card, summoner, frozenListOf())
                return null
            }
            if (req.summonerAlone(summoner)) {
                handRitual(room, player, card, summoner, frozenListOf())
                return null
            }
            val others = materials.filter { c -> c !== summoner }
            val remaining = if (req.sumMax() == req.level) {
                // One total, as always: what the summoner's level leaves
                val left = req.level - summonerLevel(summoner, others, req.level)
                if (left < 0 || !sumsExactly(others, left)) return missing
                frozenListOf(left)
            } else remainingSum(req, summoner, others)
            if (remaining.isEmpty()) return missing
            // Only those that fit, and no more than the levels left: once they add up, the rest are locked
            val fitting = remaining.flatMap { fittingSacrifices(others, it) }.distinct()
            ritualPrompt(room, player, card, summoner, fitting, "Elige las demás criaturas que se sacrifican junto a " + name(summoner)
                    + ": con el invocador, sus niveles deben sumar exactamente " + req.sumText() + ".", fitting.size)
            room.prompt!!.maxLevelSum = remaining.max()
            return null
        }

        // Rule 9.2.0.13: first the summoner (it must meet part of the Requerimiento), then the rest
        val valid = if (req.sum) summonerCandidates(materials, req) else materials.filter { req.fits(it) }
        if (valid.isEmpty()) return missing
        ritualPrompt(room, player, card, null, valid, "Elige el invocador" + (if (req.sum) "" else ": 1 criatura nivel " + req.level + (if (req.atLeast) " o mayor" else ""))
                + ". Se sacrifica y " + name(card) + " ocupa su lugar y hereda su estado.", 1)
        room.prompt!!.chosenLabel = "Invocador"
        return null
    }

    /** A Ritual whose Requerimiento names each creature (RitualParts): the summoner first, then the others, all at once. */
    private fun partsByPlayer(room: GameRoom, player: PlayerState, card: CardSlot, parts: List<RitualParts.Part>, summonerId: String?, missing: String): String? {
        val summoners = RitualParts.summoners(room, player, card, parts)
        if (summonerId == null) {
            if (summoners.isEmpty()) return missing
            ritualPrompt(room, player, card, null, summoners, RitualParts.summonerInstruction(parts, card), 1)
            room.prompt!!.chosenLabel = "Invocador"
            return null
        }
        val summoner = find(player.field, summonerId) ?: return "El invocador debe ser una criatura de tu campo"
        if (!summoners.contains(summoner)) return missing
        val others = RitualParts.others(room, player, card, parts, summoner)
        ritualPrompt(room, player, card, summoner, others, RitualParts.instruction(parts, summoner), parts.size - 1)
        room.prompt!!.min = parts.size - 1
        for (o in others) if (!room.prompt!!.cards.contains(o)) room.prompt!!.cards.add(o)
        return null
    }

    /** Whether the card can be summoned by Ritual from the hand now, for the board's legal actions. */
    @JvmStatic
    fun canRitual(room: GameRoom, userId: String?, card: CardSlot?): Boolean {
        return summoningAs(card) { canRitualAs(room, userId, card) }
    }

    @JvmStatic
    fun canRitualAs(room: GameRoom, userId: String?, card: CardSlot?): Boolean {
        val player = room.getPlayer(userId)
        val req = requirement(card)
        if (player == null || req == null || card!!.specialCost == null || !"ritual".equals(card.specialSummonKind, ignoreCase = true)) return false
        if (!canPay(room, userId, ritualCost(room, userId, card))) return false
        if (req.parts.isNotEmpty()) return RitualParts.summoners(room, player, card, req.parts).isNotEmpty()
        val materials = ritualMaterials(player, card)
        return if (req.sum) req.sumReachable(materials) else materials.any { s -> req.fits(s) && materials.count { it !== s } >= req.extra }
    }

    @JvmStatic
    fun ritualCost(room: GameRoom, userId: String?, card: CardSlot?): Int {
        val cost = card!!.specialCost!! - (if (starterAdvantage(room, userId, card, card.specialCost)) 1 else 0)
        val player = room.getPlayer(userId)
        return if (player == null) cost else max(0, cost - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, "ritual", card, null))
    }

    @JvmStatic
    fun ritualPrompt(
        room: GameRoom, player: PlayerState, card: CardSlot, summoner: CardSlot?, valid: List<CardSlot>,
        instruction: String, max: Int,
    ) {
        rulesPrompt(room, player.userId, PromptPurpose.RITUAL.id(), name(card) + " · Ritual",
                "$instruction Si no eliges ninguna, no se invoca.", "Sacrificar", valid, player.field)
        room.prompt!!.max = max
        room.prompt!!.subjectId = card.instanceId
        if (summoner != null) room.prompt!!.summonerId = summoner.instanceId
    }

    @JvmStatic
    fun handRitual(room: GameRoom, player: PlayerState, card: CardSlot, summoner: CardSlot?, others: List<CardSlot>) {
        val userId = player.userId
        val advantage = starterAdvantage(room, userId, card, card.specialCost)
        var cost = max(0, card.specialCost!! - (if (advantage) 1 else 0))
        cost -= CostModifiers.applyAutomatic(room, player, CostModifiers.Event.SPECIAL_SUMMON, "ritual", card, summoner, cost)
        pay(room, userId, cost)
        if (advantage) room.starterAdvantageUserId = null
        player.hand.remove(card)
        val used = ArrayList(frozenListOf(name(summoner)))
        others.forEach { o -> used.add(name(o)) }
        ritualSummon(room, player, card, "hand", cost, summoner, others, null,
                player.username + " invoca por Ritual a " + name(card) + " usando " + used.joinToString(", ") + " y pagando "
                        + gemsText(cost) + (if (advantage) " (ventaja del iniciador)" else ""))
        advance(room)
    }

    // ------------------------------------------------------------------ Ritual by effect (rule 9.3)

    /** Requerimiento of a Ritual: one creature of level X, or several whose levels add up to X. */
    /**
     * A Ritual's Requerimiento: "criatura nivel N [o mayor] [de fuerza M o menos]" or "criaturas que [en nivel] sumen N".
     */
    @JvmRecord
    data class Requirement(
        val sum: Boolean, val level: Int, val atLeast: Boolean, val maxStrength: Int?,
        /** "criatura nivel 2 o 3": the highest level the summoner may be (`level` without a range). */
        val maxLevel: Int = level,
        /** "criatura nivel 4 + 1 o más aliados": how many other allies are sacrificed with the summoner, at least. */
        val extra: Int = 0,
        /** Each creature used, the summoner among them ("criatura nivel 2 + 1 criatura [ángel] nivel 2 o menor"): RitualParts; empty otherwise. */
        val parts: List<RitualParts.Part> = frozenListOf(),
    ) {
        /** The totals a level-sum Requerimiento accepts: `level`, or `level`..`maxLevel` ("sumen entre 3 y 4"). */
        fun sumTargets(): List<Int> = (level..max(level, maxLevel)).toList()

        /** The highest total it accepts. */
        fun sumMax(): Int = max(level, maxLevel)

        /** "3" or "3 o 4" or "entre 3 y 5". */
        fun sumText(): String = if (sumMax() == level) "$level" else if (sumMax() == level + 1) "$level o ${sumMax()}" else "entre $level y ${sumMax()}"

        /** Some of the creatures add up exactly to one of the totals. */
        fun sumReachable(cards: List<CardSlot>): Boolean = sumTargets().any { sumsExactly(cards, it) }

        /** All these creatures together add up exactly to one of the totals. */
        fun allAddUp(cards: List<CardSlot>): Boolean = sumTargets().any { addsUpTo(cards, it) }

        /** The summoner alone meets the sum. */
        fun summonerAlone(summoner: CardSlot?): Boolean = sumTargets().any { hasLevel(summoner, it) }

        /** The creature alone meets a single-creature Requerimiento. */
        fun fits(c: CardSlot?): Boolean {
            val levelMet = levels(c).any { l -> if (atLeast) l >= this.level else l >= this.level && l <= this.maxLevel }
            return levelMet && (maxStrength == null || strength(c) <= maxStrength)
        }
    }

    @JvmStatic
    fun requirement(card: CardSlot?): Requirement? {
        // Programmed by hand in the card file (its `requirement` section): it, not the text
        val r = CardEffects.requirementOf(card)?.ritual
        if (r != null) {
            val parts = r.parts ?: frozenListOf()
            if (parts.isNotEmpty()) return Requirement(false, parts[0].level, false, null, parts[0].level, parts.size - 1, parts)
            return Requirement(r.sum, r.level, r.atLeast, r.maxStrength, r.maxLevel ?: r.level, r.extra)
        }
        // Always programmed in the card file; the printed text is only shown. Without it: not programmed
        return null
    }

    /** Creatures that can be the summoner of a level-sum Ritual: the rest of the field can complete the sum exactly. */
    @JvmStatic
    fun summonerCandidates(materials: List<CardSlot>, level: Int): List<CardSlot> {
        return materials
                .filter { s -> levels(s).any { l -> l <= level && sumsExactly(materials.filter { c -> c !== s }, level - l) } }
    }

    /** Those of a level-sum Ritual whose total may be any of its totals ("sumen entre 3 y 4"). */
    @JvmStatic
    fun summonerCandidates(materials: List<CardSlot>, req: Requirement): List<CardSlot> {
        return if (req.sumMax() == req.level) summonerCandidates(materials, req.level) else req.sumTargets().flatMap { summonerCandidates(materials, it) }.distinct()
    }

    /**
     * What the other creatures may add up to with that summoner, one value per total the Requerimiento accepts that they can complete
     * (empty: none).
     */
    @JvmStatic
    fun remainingSum(req: Requirement, summoner: CardSlot?, others: List<CardSlot>): List<Int> {
        val all: MutableList<Int> = ArrayList()
        for (total in req.sumTargets()) for (l in levels(summoner)) {
            if (l < total && sumsExactly(others, total - l) && !all.contains(total - l)) all.add(total - l)
        }
        return all
    }

    /** Whether some of the creatures' levels add up exactly to `target` ("criaturas que en nivel sumen N"). */
    /**
     * The creatures that can be sacrificed with the summoner to reach exactly `remaining` levels: each one must fit
     * (its level no more than what is left) and leave a sum the others can complete.
     */
    @JvmStatic
    fun fittingSacrifices(others: List<CardSlot>, remaining: Int): List<CardSlot> {
        return others
                .filter { c ->
                    levels(c).any { l -> l > 0 && l <= remaining
                            && (l == remaining || sumsExactly(others.filter { o -> o !== c }, remaining - l)) }
                }
    }

    /** Some of the creatures add up exactly to `target`, each counting as any level it can (Abraza Almas). */
    @JvmStatic
    fun sumsExactly(cards: List<CardSlot>, target: Int): Boolean {
        if (target < 0) return false
        var reachable = BooleanArray(target + 1)
        reachable[0] = true
        for (c in cards) {
            val next = reachable.copyOf()
            for (level in levels(c)) {
                if (level <= 0) continue
                for (t in target downTo level) if (reachable[t - level]) next[t] = true
            }
            reachable = next
        }
        return reachable[target]
    }

    /** All these creatures together add up exactly to `target`, each counting as any level it can. */
    @JvmStatic
    fun addsUpTo(cards: List<CardSlot>, target: Int): Boolean {
        var sums: MutableSet<Int> = HashSet(frozenListOf(0))
        for (c in cards) {
            val next: MutableSet<Int> = HashSet()
            val options = if (levels(c).isEmpty()) frozenListOf(0) else levels(c)
            for (s in sums) for (l in options) next.add(s + l)
            sums = next
        }
        return sums.contains(target)
    }

    /** The level the summoner of a level-sum Ritual counts as: one the others can complete, else its own. */
    @JvmStatic
    fun summonerLevel(summoner: CardSlot?, others: List<CardSlot>, total: Int): Int {
        for (l in levels(summoner)) if (l <= total && sumsExactly(others, total - l)) return l
        return if (summoner!!.getLevel() == null) 0 else summoner.getLevel()!!
    }

    /** Rule 9.2.0.10: the cards used must share a type or a color with the summoned creature. */
    @JvmStatic
    fun shares(used: CardSlot, summoned: CardSlot): Boolean {
        val color = used.colors!!.any(summoned.colors!!::contains)
        val type = ClassChanges.classes(used).any { t -> ClassChanges.classes(summoned).any { t.equals(it, ignoreCase = true) } }
        return color || type
    }

    /** Creatures that may be used for the Ritual of `target`. */
    @JvmStatic
    fun ritualMaterials(player: PlayerState, target: CardSlot): List<CardSlot> {
        // Programmed by hand: "criaturas nivel N o menor que sumen …"
        val spec = CardEffects.requirementOf(target)?.ritual
        val most = spec?.eachMaxLevel
        // "que no sea una copia de esta carta" and the like
        val filter = spec?.filter
        return player.field.filter { c -> "creature" == c.cardType && c.getLevel() != null && shares(c, target)
                && (most == null || levels(c).any { l -> l in 1..most }) && (filter == null || filter.matches(c)) }
    }

    @JvmStatic
    fun ritualFeasible(room: GameRoom, effect: PendingEffect, ritual: SpecialRule, player: PlayerState, target: CardSlot): Boolean {
        val req = requirement(target)
        if (req == null || target.specialCost == null) return false
        if (!canPay(room, player.userId, max(0, target.specialCost!! - ritual.reduce
                        - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, "ritual", target, null)))) return false
        if (req.parts.isNotEmpty()) {
            val summoners = RitualParts.summoners(room, player, target, req.parts)
            return if (ritual.selfSummoner()) find(summoners, effect.sourceInstanceId) != null else summoners.isNotEmpty()
        }
        val materials = ritualMaterials(player, target)
        if (ritual.selfSummoner()) {
            val self = find(materials, effect.sourceInstanceId) ?: return false
            if (!req.sum) return req.fits(self) && materials.count { it !== self } >= req.extra
            // The summoner is one of them: the others make up the rest
            return req.sumTargets().any { total -> levels(self).any { l -> l <= total && sumsExactly(materials.filter { c -> c !== self }, total - l) } }
        }
        if (!req.sum) return materials.any { s -> req.fits(s) && materials.count { it !== s } >= req.extra }
        return req.sumReachable(materials)
    }

    @JvmStatic
    fun ritualTargets(room: GameRoom, effect: PendingEffect, ritual: SpecialRule): List<CardSlot> {
        val player = room.getPlayer(effect.controllerId) ?: return frozenListOf()
        return zone(player, ritual.zone)
                .filter { c ->
                    "creature" == c.cardType && "ritual".equals(c.specialSummonKind, ignoreCase = true)
                            && (ritual.filter == null || ritual.filter.matches(c))
                            && summoningAs(c) { ritualFeasible(room, effect, ritual, player, c) }
                }
    }
}
