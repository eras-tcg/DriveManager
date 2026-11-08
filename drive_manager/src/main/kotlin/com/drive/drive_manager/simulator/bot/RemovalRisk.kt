package com.drive.drive_manager.simulator.bot

import com.drive.drive_manager.simulator.platform.computeIfAbsentAtomic
import com.drive.drive_manager.simulator.platform.Platform
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import com.drive.drive_manager.simulator.engine.JavaMath
import com.drive.drive_manager.simulator.engine.CardEffects
import com.drive.drive_manager.simulator.engine.EffectEngine
import com.drive.drive_manager.simulator.engine.EffectScript
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState

/**
 * frontRisk k (the user's call, 2026-11-05): a creature in the Línea frontal is where the rival's removal reaches (every
 * destruction effect of the starter decks targets the Línea frontal only); the Retaguardia is safe. The tries see a
 * removal only when the deal gives the rival one and it plays it, too noisy for a chance. So the table pays k times the
 * expected loss of the creature of its own most at risk there: for each one, the chance that the rival holds or draws
 * (its hand and one more card, from its hand and deck: what the bot does not see) a destruction it can pay next turn
 * that reaches it (by the strength and level the effect's text allows), times what the creature is worth.
 */
internal object RemovalRisk {

    /** What a destruction effect reaches: strength and level at most, or a level exactly (-1: no limit). */
    private class Reach(val cost: Int, val maxStrength: Int, val maxLevel: Int, val level: Int) {
        fun hits(c: CardSlot): Boolean {
            val str = EffectEngine.strength(c)
            val lvl = if (c.getLevel() == null) 0 else c.getLevel()!!
            return (maxStrength < 0 || str <= maxStrength) && (maxLevel < 0 || lvl <= maxLevel) && (level < 0 || lvl == level)
        }
    }

    private val STRENGTH: Regex = Regex("fuerza (\\d+) o menos")
    private val LEVEL_MAX: Regex = Regex("nivel (\\d+) o menor")
    private val LEVEL: Regex = Regex("nivel (\\d+)(?! o)")
    /** The destructions of each card, by name (none: not a removal). */
    private val BY_CARD: MutableMap<String, List<Reach>> = Platform.concurrentMap()
    /**
     * riskCuts (the user's ST1 games, 2026-11-05): a creature reduced to 0 is destroyed, so a strength reduction of N is a
     * removal of every creature of strength N or less (Arc-xioma, Mathaus, Bertalum; Hez all of the Línea frontal at once).
     * The cuts of each card, by name; and the rival's creatures on the field that cut on their own (an activated effect,
     * one at the start of its turn) threaten for sure next turn, when they have the materials it asks to tribute.
     */
    private val CUTS_BY_CARD: MutableMap<String, List<Reach>> = Platform.concurrentMap()
    private val CUT: Regex = Regex("(?:reduce|reducir)[^.;]*?fuerza en (\\d+)|(?:reduce|reducir) en (\\d+) su fuerza")
    private val MATERIALS: Regex = Regex("tributar (\\d+) materiales?")

    /** The penalty each creature of its own in the Línea frontal carries when the rival's turn begins, by instanceId. */
    @JvmStatic
    fun risks(room: GameRoom?, mine: PlayerState?, rival: PlayerState?, w: BotSettings): Map<String?, Double> {
        val out: MutableMap<String?, Double> = HashMap()
        val k = w.get("frontRisk")
        if (k == 0.0 || mine == null || rival == null) return out
        val pool: MutableList<CardSlot> = ArrayList(rival.hand)
        pool.addAll(rival.deck)
        if (pool.isEmpty()) return out
        val draws = rival.hand.size + 1
        // The gems the rival will have: its Marcador next turn, one more paid below 0 at most
        val gems = min(5, rival.resourceCount + 1) + 1
        // riskLives L (the user's call, 2026-11-05): with few lives of its own the bot must take risks (a creature kept
        // back lets the hits through), so the penalty is times 1 - e^(-lives / L): about 0.4 with 1 life, 0.6 with 2 (L=2)
        val lives = w.get("riskLives")
        val frail = if (lives > 0) 1 - exp(-mine.lifeStack.size / lives) else 1.0
        // riskCuts c: the cuts count c times what a destruction does (they often ask materials or a cost: 0.5 at half)
        val cutWeight = max(0.0, min(1.0, w.get("riskCuts")))
        val cuts = cutWeight > 0
        // The cuts the rival's creatures on the field make next turn (riskCuts)
        val sure: MutableList<Reach> = ArrayList()
        if (cuts) for (r in rival.field) if ("creature" == r.cardType) sure.addAll(fieldCuts(r))
        for (c in mine.field) {
            if ("creature" != c.cardType || !EffectEngine.inFrontLine(c)) continue
            val reaching = pool.count { r -> reaches(r, c, gems) }
            var p = if (reaching == 0) 0.0 else 1 - (1 - reaching.toDouble() / pool.size).pow(draws.toDouble())
            if (cuts) {
                val cutting = pool.count { r -> !reaches(r, c, gems) && RemovalRisk.cuts(r, c, gems) }
                var pc = if (cutting == 0) 0.0 else 1 - (1 - cutting.toDouble() / pool.size).pow(draws.toDouble())
                if (sure.any { r -> r.hits(c) }) pc = 1.0
                p = 1 - (1 - p) * (1 - cutWeight * pc)
            }
            if (p <= 0) continue
            out[c.instanceId] = k * frail * p * CardWorth.of(c, w)
        }
        return out
    }

    /**
     * The penalty of the worst exposed one. riskAlive (the user's call, 2026-11-05): the try goes on through the rival's
     * turn, and a creature it destroyed there (an attack on it exhausted, a removal dealt) is already lost on the table:
     * charging its risk too counted it twice, and throwing it away in a useless attack looked as good as a hit on the
     * lives (Armadura De Plata against Krokler). So only those still on its field at the end of the try (alive) pay.
     */
    @JvmStatic
    fun charge(risks: Map<String?, Double>?, mine: PlayerState?, w: BotSettings): Double {
        if (risks == null || risks.isEmpty()) return 0.0
        val alive = mine != null && w.get("riskAlive") >= 0.5
        val each: MutableList<Double> = ArrayList()
        for (e in risks.entries) {
            if (alive && mine!!.field.none { c -> e.key!! == c.instanceId }) continue
            each.add(e.value)
        }
        // riskSum n (the user's st2 games, 2026-11-05): the rival may play more than one removal (the mirror's Ddraig,
        // Fadrake, Llamarada…), so the n creatures most at risk pay, not only the worst: several bodies within the reach
        // of the removals cost more than one tower beyond it (0 or 1: the worst only, as before)
        each.sortWith(reverseOrder())
        val n = max(1, JavaMath.round(w.get("riskSum")).toInt())
        var sum = 0.0
        for (i in 0 until min(n, each.size)) sum += each[i]
        return sum
    }

    private fun reaches(removal: CardSlot, target: CardSlot, gems: Int): Boolean {
        val cost = removal.cost
        if (cost == null || cost > gems) return false
        return reaches(removal).any { r -> r.hits(target) }
    }

    private fun reaches(card: CardSlot): List<Reach> {
        val key = if (card.cardName == null) "" else BotSettings.cardKey(card.cardName)
        return BY_CARD.computeIfAbsentAtomic(key) {
            val out: MutableList<Reach> = ArrayList()
            for (e in CardEffects.of(card)) {
                val t = if (e.text == null) "" else e.text!!.lowercase()
                if (!t.contains("destrú") || !t.contains("enemigo")) continue
                out.add(Reach(if (card.cost == null) 0 else card.cost!!, first(STRENGTH, t), first(LEVEL_MAX, t),
                        if (LEVEL_MAX.containsMatchIn(t)) -1 else first(LEVEL, t)))
            }
            out
        }
    }

    private fun cuts(card: CardSlot, target: CardSlot, gems: Int): Boolean {
        val cost = card.cost
        if (cost == null || cost > gems) return false
        return cutsOf(card).any { r -> r.hits(target) }
    }

    /** The strength reductions on enemies a card's effects make: each reaches strength N or less. */
    private fun cutsOf(card: CardSlot): List<Reach> {
        val key = if (card.cardName == null) "" else BotSettings.cardKey(card.cardName)
        return CUTS_BY_CARD.computeIfAbsentAtomic(key) {
            val out: MutableList<Reach> = ArrayList()
            for (e in CardEffects.of(card)) {
                val t = if (e.text == null) "" else e.text!!.lowercase()
                if (!t.contains("enemigo")) continue
                val m = CUT.find(t)
                if (m != null) out.add(Reach(if (card.cost == null) 0 else card.cost!!, (m.groups[1]?.value ?: m.groupValues[2]).toInt(), -1, -1))
            }
            out
        }
    }

    /** The cuts a creature on the field makes by itself next turn (activated, or at the start of the turn), if it can pay. */
    private fun fieldCuts(creature: CardSlot): List<Reach> {
        val out: MutableList<Reach> = ArrayList()
        for (e in CardEffects.of(creature)) {
            val t = if (e.text == null) "" else e.text!!.lowercase()
            val own = e.moment == EffectScript.Moment.ACTIVATED || t.contains("al inicio del turno")
            if (!own || !t.contains("enemigo")) continue
            val m = CUT.find(t)
            if (m == null) continue
            val need = first(MATERIALS, t)
            if (need > 0 && creature.materials!!.size < need) continue
            out.add(Reach(0, (m.groups[1]?.value ?: m.groupValues[2]).toInt(), -1, -1))
        }
        return out
    }

    private fun first(p: Regex, t: String): Int {
        val m = p.find(t)
        return if (m != null) m.groupValues[1].toInt() else -1
    }
}
