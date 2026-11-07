@file:kotlin.jvm.JvmName("GameShuffle")

package com.eras.engine

/**
 * The game's chance, the same on the server and in the browser. It gives exactly the numbers of
 * `java.util.Random` (its 48-bit linear congruential generator) and shuffles exactly as `Collections.shuffle`,
 * so a seed and the actions played reproduce a game anywhere: a replay recorded on the server (MatchReplay)
 * plays out the same in the browser, and a game played offline the same on the server.
 *
 * Not exported to JavaScript: a 64-bit Long only crosses that boundary as a BigInt. The site reaches the engine
 * through [EngineApi], with seeds as decimal strings.
 */
class GameRandom(seed: Long) {
    private var state: Long = scramble(seed)

    /** Starts over from [seed], as `java.util.Random.setSeed`. */
    fun setSeed(seed: Long) {
        state = scramble(seed)
    }

    /** The next [bits] random bits (1–32), as `java.util.Random.next`. */
    private fun next(bits: Int): Int {
        state = (state * MULTIPLIER + ADDEND) and MASK
        return (state ushr (48 - bits)).toInt()
    }

    /** As `java.util.Random.nextInt()`. */
    fun nextInt(): Int = next(32)

    /** A number from 0 (included) to [bound] (excluded), as `java.util.Random.nextInt(int)`. */
    fun nextIntBelow(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        var r = next(31)
        val m = bound - 1
        if (bound and m == 0) return ((bound.toLong() * r.toLong()) shr 31).toInt()
        var u = r
        while (true) {
            r = u % bound
            if (u - r + m >= 0) return r
            u = next(31)
        }
    }

    /** As `java.util.Random.nextLong()`. */
    fun nextLong(): Long = (next(32).toLong() shl 32) + next(32)

    /** As `java.util.Random.nextBoolean()`. */
    fun nextBoolean(): Boolean = next(1) != 0

    /** As `java.util.Random.nextDouble()`. */
    fun nextDouble(): Double = ((next(26).toLong() shl 27) + next(27)) * DOUBLE_UNIT

    companion object {
        private const val MULTIPLIER = 0x5DEECE66DL
        private const val ADDEND = 0xBL
        private const val MASK = (1L shl 48) - 1
        private const val DOUBLE_UNIT = 1.0 / (1L shl 53)

        private fun scramble(seed: Long): Long = (seed xor MULTIPLIER) and MASK
    }
}

/**
 * Shuffles [list] in place exactly as `Collections.shuffle(list, random)` does on a list with random access: from
 * the last position down, each card swaps with one at or before it.
 */
fun <T> MutableList<T>.shuffleAs(random: GameRandom) {
    for (i in size downTo 2) {
        val j = random.nextIntBelow(i)
        val swap = this[i - 1]
        this[i - 1] = this[j]
        this[j] = swap
    }
}
