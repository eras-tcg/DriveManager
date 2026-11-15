package com.drive.drive_manager.simulator.bot

import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmRecord
import com.drive.drive_manager.simulator.platform.computeIfAbsentAtomic
import com.drive.drive_manager.simulator.platform.Platform
import com.drive.drive_manager.simulator.engine.CardEffects
import com.drive.drive_manager.simulator.engine.EffectEngine
import com.drive.drive_manager.simulator.engine.EffectScript
import com.drive.drive_manager.simulator.room.CardSlot

/**
 * What a card is worth to the look-ahead bot, by the weights of its [BotSettings]: its stats (cost,
 * strength, abilities, level, what it carries) and a bonus for its effects. The bonus does not read what an effect
 * does, only how many effects of each kind the card has (continuous, activated, each turn or on an event, on summon,
 * on play, on attack, on destruction, inherited as a Recurso): a card with a slow effect is worth more than a bare
 * one of the same stats, by as much as the tuning finds. Each card's own biases by zone are added where the card is
 * ([TableScore.side]).
 */
internal object CardWorth {

    /** How many effects of each kind a card has, by its name (cards never change their programmed effects). */
    @JvmRecord
    data class Profile(val continuous: Int, val activated: Int, val event: Int, val summon: Int, val play: Int, val attack: Int,
                       val destroy: Int, val inherited: Int)

    private val PROFILES: MutableMap<String, Profile> = Platform.concurrentMap()

    @JvmStatic
    fun of(card: CardSlot?, w: BotSettings): Double {
        if (card == null) return 0.0
        var v: Double = if (card.cost == null) 2.0 else card.cost!!.toDouble()
        if ("creature" == card.cardType) {
            v *= w.get("vCost")
            val strength = EffectEngine.strength(card)
            v += strength * w.vStr + strength * strength * w.vStrSq
            v += EffectEngine.keywords(card).size * w.vKeyword
            if (card.getLevel() != null) v += card.getLevel()!! * w.vLevel
        }
        v += (card.materials!!.size + card.resources!!.size) * w.vAttached
        val p = profile(card)
        return v + p.continuous * w.eContinuous + p.activated * w.eActivated + p.event * w.eEvent + p.summon * w.eSummon +
                p.play * w.ePlay + p.attack * w.eAttack + p.destroy * w.eDestroy + p.inherited * w.eInherited
    }

    @JvmStatic
    fun profile(card: CardSlot): Profile {
        val name = if (card.cardName == null) "" else card.cardName!!.trim { it <= ' ' }.lowercase()
        return PROFILES.computeIfAbsentAtomic(name) { n ->
            var activated = 0
            var event = 0
            var summon = 0
            var play = 0
            var attack = 0
            var destroy = 0
            for (e in CardEffects.of(n)) {
                when (e.moment!!) {
                    EffectScript.Moment.ACTIVATED -> activated++
                    EffectScript.Moment.ON_SUMMON -> summon++
                    EffectScript.Moment.ON_PLAY -> play++
                    EffectScript.Moment.ON_ATTACK, EffectScript.Moment.ON_ATTACK_END -> attack++
                    EffectScript.Moment.ON_DESTROY -> destroy++
                    else -> event++
                }
            }
            Profile(CardEffects.continuousOf(card).size, activated, event, summon, play, attack, destroy,
                    CardEffects.inheritedOf(n).size)
        }
    }
}
