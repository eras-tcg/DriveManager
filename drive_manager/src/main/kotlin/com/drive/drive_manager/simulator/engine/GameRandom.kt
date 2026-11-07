package com.drive.drive_manager.simulator.engine

import java.util.Random

/**
 * The game's shuffles in one place: exactly `Collections.shuffle(list, random)` (from the last position down,
 * each element swaps with one at or before it), written out so the engine in Kotlin, which has no
 * `java.util.Collections`, can do the same shuffle from the same seed (see eras-engine's GameRandom). A replay
 * kept by the server plays out the same anywhere only if every shuffle draws the same numbers.
 */
object GameRandom {

    @JvmStatic
    fun <T> shuffle(list: MutableList<T>, random: Random?) {
        var i = list.size
        while (i > 1) {
            val j = random!!.nextInt(i)
            list[i - 1] = list.set(j, list[i - 1])
            i--
        }
    }
}
