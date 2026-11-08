package com.drive.drive_manager.simulator.bot

import kotlin.jvm.JvmStatic
import kotlin.math.pow
import com.drive.drive_manager.simulator.engine.frozenListOf
import com.drive.drive_manager.simulator.engine.JavaMath
import com.drive.drive_manager.simulator.engine.GameEvent
import com.drive.drive_manager.simulator.room.CardLocator
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState
import com.drive.drive_manager.simulator.room.Zone

/**
 * lifeExp: a hit on a hidden life is worth what such a hit is worth on average, not what the one card dealt in that
 * try made of it. By the rules (CombatRules.directDamage) the life a hit of D removes is a loss for its owner only if
 * it is a creature of strength D or less (to the Tributos); a stronger creature goes back as a life (Reforzar: no
 * life lost) and a utility or structure goes to the hand. The average is over the cards the hit could have been: the
 * defender's deck, face-down lives and, for the rival, its hand (what the bot does not know). So a weak attacker into
 * lives of big creatures is worth little, a strong one much, and the luck of one deal no longer swings a try.
 * lifeCurveExp (2026-11-05): with lifeFoe / lifeMine the table values the next life life * c^(lives left), not life;
 * the correction took life off and so undid more than the table had given (a hit far from the win came out at about
 * nothing: Armadura De Plata thrown at Krokler looked as good). Then the life lost is valued with the same curve.
 */
internal object LifeHits {

    /** The sum, over the hits on lives in a try since `from`, of the average hit's value minus the one dealt. */
    @JvmStatic
    fun correction(copy: GameRoom, me: String?, from: Int, w: BotSettings): Double {
        val tl = copy.timeline
        var c = 0.0
        for (i in from until tl.size) {
            val e = tl[i]
            if (GameEvent.LIFE_DAMAGE != e.type || e.data == null) continue
            val d = e.data!!["damage"]
            val def = e.data!!["defenderId"]
            if (d !is Number || def == null) continue
            val dmg: Number = d
            val defender = copy.getPlayer(def.toString())
            val life = find(defender, e.cardInstanceId)
            if (defender == null || life == null) continue
            val pool: MutableList<CardSlot> = ArrayList(defender.deck)
            for (l in defender.lifeStack) if (l.faceDown) pool.add(l)
            if (me!! != defender.userId) pool.addAll(defender.hand)
            pool.add(life)
            val c0 = w.get(if (me == defender.userId) "lifeMine" else "lifeFoe")
            val worth = if (w.get("lifeCurveExp") >= 0.5 && c0 > 0 && c0 < 1) w.life * c0.pow(defender.lifeStack.size.toDouble()) else w.life
            // DoubleStream.average() sums with compensation (Kahan): Kotlin's average() would round differently
            val avg = JavaMath.average(pool) { x -> value(x, dmg.toInt(), w, worth) } ?: 0.0
            val diff = avg - value(life, dmg.toInt(), w, worth)
            // For the bot: its hits on the rival count for it (times aggression), the rival's on it against it
            c += if (me == defender.userId) -diff else w.aggression * diff
        }
        return c
    }

    /** What a hit of `damage` that reveals this card takes from its owner. */
    private fun value(c: CardSlot, damage: Int, w: BotSettings, life: Double): Double {
        if ("creature" == c.cardType) {
            return if (c.strength == null || c.strength!! <= damage) life else 0.0
        }
        return life - w.hand
    }

    /** Where a hit life may end up (or be): tributes, hand, lives, field, discard pile. */
    private val SEARCHED: List<Zone> = frozenListOf(Zone.TRIBUTE_ZONE, Zone.HAND, Zone.LIFE_STACK, Zone.FIELD, Zone.DISCARD_PILE)

    private fun find(p: PlayerState?, id: String?): CardSlot? {
        return CardLocator.find(p, id, SEARCHED, false)
    }
}
