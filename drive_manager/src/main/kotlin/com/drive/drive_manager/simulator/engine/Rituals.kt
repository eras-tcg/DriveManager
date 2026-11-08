package com.drive.drive_manager.simulator.engine

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
            return name(card) + " no se invoca por Ritual"
        }
        if (askTap(room, player, card, "ritual", summonerId)) return null
        val cost = ritualCost(room, userId, card)
        if (!canPay(room, userId, cost)) return "No tienes gemas suficientes: tienes " + gemsOf(room, userId) + " y el coste especial es " + cost
        val materials = ritualMaterials(player, card)
        val missing = "Tus criaturas no cumplen el Requerimiento de " + name(card) + ": " + card.requirement!!.trim { it <= ' ' }

        if (summonerId != null) {
            val summoner = find(player.field, summonerId) ?: return "El invocador debe ser una criatura de tu campo"
            if (!materials.contains(summoner)) return name(summoner) + " no comparte tipo ni color con " + name(card)
            if (!req.sum) {
                if (!req.fits(summoner)) return missing
                handRitual(room, player, card, summoner, frozenListOf())
                return null
            }
            if (hasLevel(summoner, req.level)) {
                handRitual(room, player, card, summoner, frozenListOf())
                return null
            }
            val others = materials.filter { c -> c !== summoner }
            val remaining = req.level - summonerLevel(summoner, others, req.level)
            if (remaining < 0 || !sumsExactly(others, remaining)) return missing
            // Only those that fit, and no more than the levels left: once they add up, the rest are locked
            val fitting = fittingSacrifices(others, remaining)
            ritualPrompt(room, player, card, summoner, fitting, "Elige las demás criaturas que se sacrifican junto a " + name(summoner)
                    + ": con el invocador, sus niveles deben sumar exactamente " + req.level + ".", fitting.size)
            room.prompt!!.maxLevelSum = remaining
            return null
        }

        // Rule 9.2.0.13: first the summoner (it must meet part of the Requerimiento), then the rest
        val valid = if (req.sum) summonerCandidates(materials, req.level) else materials.filter { req.fits(it) }
        if (valid.isEmpty()) return missing
        ritualPrompt(room, player, card, null, valid, "Elige el invocador" + (if (req.sum) "" else ": 1 criatura nivel " + req.level + (if (req.atLeast) " o mayor" else ""))
                + ". Se sacrifica y " + name(card) + " ocupa su lugar y hereda su estado.", 1)
        room.prompt!!.chosenLabel = "Invocador"
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
        val materials = ritualMaterials(player, card)
        return if (req.sum) sumsExactly(materials, req.level) else materials.any { req.fits(it) }
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
    data class Requirement(val sum: Boolean, val level: Int, val atLeast: Boolean, val maxStrength: Int?) {
        /** The creature alone meets a single-creature Requerimiento. */
        fun fits(c: CardSlot?): Boolean {
            val levelMet = levels(c).any { l -> if (atLeast) l >= this.level else l == this.level }
            return levelMet && (maxStrength == null || strength(c) <= maxStrength)
        }
    }

    @JvmStatic
    fun requirement(card: CardSlot?): Requirement? {
        if (card!!.requirement == null) return null
        val text = card.requirement!!.lowercase()
        val m = Regex("nivel\\s*(\\d+)|sumen\\s*(\\d+)").find(text)
        if (m == null) return null
        val level = (m.groups[1]?.value ?: m.groupValues[2]).toInt()
        val atLeast = Regex("nivel\\s*\\d+\\s*o\\s*mayor").containsMatchIn(text)
        val strength = Regex("fuerza\\s*(\\d+)\\s*o\\s*menos").find(text)
        return Requirement(text.contains("sum"), level, atLeast, if (strength != null) strength.groupValues[1].toInt() else null)
    }

    /** Creatures that can be the summoner of a level-sum Ritual: the rest of the field can complete the sum exactly. */
    @JvmStatic
    fun summonerCandidates(materials: List<CardSlot>, level: Int): List<CardSlot> {
        return materials
                .filter { s -> levels(s).any { l -> l <= level && sumsExactly(materials.filter { c -> c !== s }, level - l) } }
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
        val type = used.classes!!.any { t -> summoned.classes!!.any { t.equals(it, ignoreCase = true) } }
        return color || type
    }

    /** Creatures that may be used for the Ritual of `target`. */
    @JvmStatic
    fun ritualMaterials(player: PlayerState, target: CardSlot): List<CardSlot> {
        return player.field.filter { c -> "creature" == c.cardType && c.getLevel() != null && shares(c, target) }
    }

    @JvmStatic
    fun ritualFeasible(room: GameRoom, effect: PendingEffect, ritual: SpecialRule, player: PlayerState, target: CardSlot): Boolean {
        val req = requirement(target)
        if (req == null || target.specialCost == null) return false
        if (!canPay(room, player.userId, max(0, target.specialCost!! - ritual.reduce
                        - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, "ritual", target, null)))) return false
        val materials = ritualMaterials(player, target)
        if (ritual.selfSummoner()) {
            val self = find(materials, effect.sourceInstanceId) ?: return false
            if (!req.sum) return req.fits(self)
            // The summoner is one of them: the others make up the rest
            return levels(self).any { l -> l <= req.level && sumsExactly(materials.filter { c -> c !== self }, req.level - l) }
        }
        if (!req.sum) return materials.any { req.fits(it) }
        return sumsExactly(materials, req.level)
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
