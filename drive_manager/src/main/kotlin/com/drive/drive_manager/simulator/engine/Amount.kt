package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.room.GameRoom

/**
 * A number an effect reads from the game ("reduciendo su coste en X, donde X es …", "gana fuerza 1 por cada aliado
 * [legionario]"), as data: a closed vocabulary of records, like [Condition].
 */
sealed interface Amount {

    fun of(room: GameRoom, controller: String?): Int

    /** How many cards the last action moved ("por cada material tributado"): the effect's own, read by EngineContext. */
    data object LastCount : Amount {
        override fun of(room: GameRoom, controller: String?): Int {
            return 0
        }
    }

    /** "esta carta infringe daño directo": the strength of the card acting, read by EngineContext. */
    data object SourceStrength : Amount {
        override fun of(room: GameRoom, controller: String?): Int {
            return 0
        }
    }

    /** The level of the ally chosen before, read by EngineContext. */
    data object SubjectLevel : Amount {
        override fun of(room: GameRoom, controller: String?): Int {
            return 0
        }
    }

    /** "la cantidad de aliados utilizados para invocar esta carta", read by EngineContext. */
    data object UsedToSummon : Amount {
        override fun of(room: GameRoom, controller: String?): Int {
            return 0
        }
    }

    /** How many cards of your hand are over that number. */
    @JvmRecord
    data class HandOver(val keep: Int) : Amount {
        override fun of(room: GameRoom, controller: String?): Int {
            return Math.max(0, room.getPlayer(controller)!!.hand.size - keep)
        }
    }

    /** "en el nivel de dicho aliado": the card of the triggering event, read by EngineContext. */
    data object TriggerSourceLevel : Amount {
        override fun of(room: GameRoom, controller: String?): Int {
            return 0
        }
    }

    /** How many cards on both fields match. */
    @JvmRecord
    data class OnBothFields(val filter: CardFilter?) : Amount {
        override fun of(room: GameRoom, controller: String?): Int {
            val f = filter!!
            return room.players.values.sumOf { p -> p.field.count { f.matches(it) } }
        }
    }

    /** How many lives a player has; `faceDownOnly`: "bocabajo". Without an opponent yet, more than any card asks. */
    @JvmRecord
    data class Lives(val opponent: Boolean, val faceDownOnly: Boolean) : Amount {
        override fun of(room: GameRoom, controller: String?): Int {
            val id = if (opponent) room.getOpponentId(controller) else controller
            val p = if (id == null) null else room.getPlayer(id)
            if (p == null) return Int.MAX_VALUE
            return if (faceDownOnly) p.lifeStack.count { it.faceDown } else p.lifeStack.size
        }
    }

    /** The sum of the levels of the opponent's creatures. */
    data object OpponentLevels : Amount {
        override fun of(room: GameRoom, controller: String?): Int {
            return EffectEngine.levelsOnField(room, room.getOpponentId(controller), CardFilter.creatures())
        }
    }

    /** The opponent's creatures in the Línea frontal. Without an opponent yet, one (so "no hay enemigos" does not hold). */
    data object EnemiesInFrontLine : Amount {
        override fun of(room: GameRoom, controller: String?): Int {
            val p = room.getPlayer(room.getOpponentId(controller)) ?: return 1
            return p.field.count { c -> "creature" == c.cardType && EffectEngine.inFrontLine(c) }
        }
    }

    /** Your gems as they are: below 0 when you owe them ("si tienes menos de 0 gemas"). */
    data object SignedGems : Amount {
        override fun of(room: GameRoom, controller: String?): Int {
            return EffectEngine.gemsOf(room, controller)
        }
    }

    /** A fixed number. */
    @JvmRecord
    data class Fixed(val value: Int) : Amount {
        override fun of(room: GameRoom, controller: String?): Int {
            return value
        }
    }

    /** How many of your cards on the field match. */
    @JvmRecord
    data class OnField(val filter: CardFilter?) : Amount {
        override fun of(room: GameRoom, controller: String?): Int {
            return EffectEngine.countOnField(room, controller, filter).toInt()
        }
    }

    /** How many cards of one of your zones ("tributeZone", "hand") match. */
    @JvmRecord
    data class InZone(val zone: String?, val filter: CardFilter?) : Amount {
        override fun of(room: GameRoom, controller: String?): Int {
            return EffectEngine.countInZone(room, controller, zone, filter).toInt()
        }
    }

    /** The sum of the levels of your creatures on the field that match. */
    @JvmRecord
    data class LevelsOnField(val filter: CardFilter?) : Amount {
        override fun of(room: GameRoom, controller: String?): Int {
            return EffectEngine.levelsOnField(room, controller, filter)
        }
    }

    /** `base / divisor + plus` ("aumenta en 1 el nivel por cada 2 copias": 1 + copias / 2). */
    @JvmRecord
    data class Scaled(val base: Amount?, val divisor: Int, val plus: Int) : Amount {
        override fun of(room: GameRoom, controller: String?): Int {
            return base!!.of(room, controller) / divisor + plus
        }
    }

    /** `base × factor` ("gana fuerza 3 por cada copia": copias × 3). */
    @JvmRecord
    data class Times(val base: Amount?, val factor: Int) : Amount {
        override fun of(room: GameRoom, controller: String?): Int {
            return base!!.of(room, controller) * factor
        }
    }

    /** One amount while a condition on the table holds, another otherwise ("si tienes 3 o menos vidas: …"). */
    @JvmRecord
    data class IfThen(val condition: Condition?, val then: Amount?, val otherwise: Amount?) : Amount {
        override fun of(room: GameRoom, controller: String?): Int {
            return if (condition!!.test(room, null, null, controller)) then!!.of(room, controller) else otherwise!!.of(room, controller)
        }
    }

    /** How many more lives the opponent has than you (0 when you have as many or more): "por cada vida más que tenga tu oponente que tú". */
    @JvmRecord
    data class LivesBehind(val faceDownOnly: Boolean) : Amount {
        override fun of(room: GameRoom, controller: String?): Int {
            val you = room.getPlayer(controller)
            val opponent = room.getPlayer(room.getOpponentId(controller))
            if (you == null || opponent == null) return 0
            val mine = you.lifeStack.count { l -> !faceDownOnly || l.faceDown }.toLong()
            val theirs = opponent.lifeStack.count { l -> !faceDownOnly || l.faceDown }.toLong()
            return Math.max(0, theirs - mine).toInt()
        }
    }

    /** How many cards of several of your zones ("de tu descarte y tributos") match. */
    @JvmRecord
    data class InZones(val zones: String?, val filter: CardFilter?) : Amount {
        override fun of(room: GameRoom, controller: String?): Int {
            var n = 0
            // Java's String.split: the empty strings at the end are dropped
            for (z in JavaText.split(COMMA, zones!!)) n += EffectEngine.countInZone(room, controller, z.trim { it <= ' ' }, filter).toInt()
            return n
        }

        private companion object {
            val COMMA: Regex = Regex(",")
        }
    }

    /** Your gems now ("el valor de tu marcador de gemas"), 0 when below 0. */
    data object Gems : Amount {
        override fun of(room: GameRoom, controller: String?): Int {
            return Math.max(0, EffectEngine.gemsOf(room, controller))
        }
    }

    companion object {
        @JvmStatic
        fun fixed(value: Int): Amount {
            return Fixed(value)
        }
    }
}
