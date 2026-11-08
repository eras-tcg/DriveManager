package com.drive.drive_manager.simulator.bot

import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import com.drive.drive_manager.simulator.platform.computeIfAbsentAtomic
import com.drive.drive_manager.simulator.platform.Platform
import kotlin.math.max
import com.drive.drive_manager.simulator.engine.CardEffects
import com.drive.drive_manager.simulator.engine.EffectScript
import com.drive.drive_manager.simulator.room.CardSlot

/**
 * What a creature's effects do to a combat (the user's call, 2026-11-05: CombatSearch must see them; Mawser destroys a
 * second creature when it wins a clash, which made pressing it a dramatic loss). Read once per card from the effects'
 * texts, as RemovalRisk reads the destructions, so it works for any card written the same way:
 * - `bonusLevel`: "si esta carta destruye algún enemigo, elige 1 enemigo nivel N o menor; destrúyelo"
 *   (Mawser): each clash it wins takes one more enemy of level N or less.
 * - `attackStr` / `attackLevel`: an effect on attack that destroys an enemy of strength / level N or
 *   less (Fadrake, Zhaglizer, Zhauz, Dracoventus); `attackTributes` tributes it must send to pay it and
 *   `attackSacrifice` an ally it must sacrifice (it then needs one).
 * - `refundCards`: cards it draws when destroyed less half the ones it tributes then (Damon, Virdris,
 *   Carnhaus); `refundSummon`: a summon when destroyed (Slicker, Espada De Plata). Losing it costs less.
 * - `attackSummon`: a summon or evolution when it attacks (Flamagón, Saildreik, Caballero Umbrío, Comandante,
 *   Caballero Real): attacking is worth something even when it dies.
 *
 * -1 / 0 / false: none. Reactivating, gaining Letalidad or Enfrentar are not read.
 */
@JvmRecord
internal data class CombatTraits(val bonusLevel: Int, val attackStr: Int, val attackLevel: Int, val attackTributes: Int,
                                 val attackSacrifice: Boolean, val refundCards: Double, val refundSummon: Boolean,
                                 val attackSummon: Boolean) {

    fun attackKills(): Boolean {
        return attackStr >= 0 || attackLevel >= 0
    }

    companion object {
        @JvmField
        val NONE = CombatTraits(-1, -1, -1, 0, false, 0.0, false, false)

        private val LEVEL: Regex = Platform.regex("nivel (\\d+) o menor")
        private val STRENGTH: Regex = Platform.regex("fuerza (\\d+) o menos")
        private val DRAW: Regex = Platform.regex("roba (\\d+) carta")
        private val TRIBUTE: Regex = Platform.regex("tributa (\\d+) carta")
        private val SEND: Regex = Platform.regex("enviar (\\d+) de tus tributos")
        private val BY_CARD: MutableMap<String, CombatTraits> = Platform.concurrentMap()

        @JvmStatic
        fun of(card: CardSlot?): CombatTraits {
            if (card == null || card.cardName == null) return NONE
            // A tower's traits are its own and those its Recursos give it: the key names them all
            val key = StringBuilder(BotSettings.cardKey(card.cardName))
            card.resources!!.map { r -> if (r.cardName == null) "" else BotSettings.cardKey(r.cardName) }.sorted()
                    .forEach { n -> key.append('|').append(n) }
            return BY_CARD.computeIfAbsentAtomic(key.toString()) { read(card) }
        }

        private fun read(card: CardSlot): CombatTraits {
            var bonus = -1
            var aStr = -1
            var aLevel = -1
            var tributes = 0
            var sacrifice = false
            var refundSummon = false
            var attackSummon = false
            var refund = 0.0
            // CardEffects.of(card) holds the effects its Recursos give it too (a tower's inherited ones)
            for (e in CardEffects.of(card)) {
                val t = if (e.text == null) "" else e.text!!.lowercase()
                val destroysEnemy = t.contains("destrú") && t.contains("enemigo")
                when (e.moment!!) {
                    EffectScript.Moment.ON_ATTACK -> {
                        if (destroysEnemy && !t.contains("vidas")) {
                            aStr = first(STRENGTH, t)
                            aLevel = first(LEVEL, t)
                            if (aStr < 0 && aLevel < 0) aLevel = 99
                            tributes = max(0, first(SEND, t))
                            sacrifice = t.contains("sacrificar")
                        }
                        if (t.contains("invoca")) attackSummon = true
                    }
                    EffectScript.Moment.ON_DESTROY -> {
                        val draw = first(DRAW, t)
                        val trib = first(TRIBUTE, t)
                        if (draw > 0) refund = draw - 0.5 * max(0, trib)
                        if (t.contains("invoca")) refundSummon = true
                    }
                    else -> {
                        if (t.contains("si esta carta destruye") && destroysEnemy) bonus = max(0, if (first(LEVEL, t) < 0) 99 else first(LEVEL, t))
                    }
                }
            }
            return CombatTraits(bonus, aStr, aLevel, tributes, sacrifice, max(0.0, refund), refundSummon, attackSummon)
        }

        private fun first(p: Regex, t: String): Int {
            val m = p.find(t)
            return if (m != null) m.groupValues[1].toInt() else -1
        }
    }
}
