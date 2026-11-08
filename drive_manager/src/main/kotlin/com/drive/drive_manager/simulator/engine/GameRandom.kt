package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.platform.Platform

/**
 * The game's chance: exactly the numbers of `java.util.Random` (its 48-bit linear congruential generator, the same
 * `nextInt(bound)`, `nextLong`, `nextDouble`, `nextBoolean`, and `nextGaussian` with its cached second value), written
 * out in plain Kotlin so the engine draws the same numbers from the same seed on any platform (see eras-engine's
 * GameRandom). A replay kept by the server plays out the same anywhere only if every draw is the same.
 *
 * Not thread-safe (java.util.Random is): a room's chance is used by one thread at a time, under the room's lock.
 */
class GameRandom(seed: Long) {

    private var state: Long = scramble(seed)
    private var nextNextGaussian = 0.0
    private var haveNextNextGaussian = false

    /** A chance nobody seeded, different each time (as `new java.util.Random()`). */
    constructor() : this(Platform.randomSeed())

    /** Starts over from `seed`, as `java.util.Random.setSeed`. */
    fun setSeed(seed: Long) {
        state = scramble(seed)
        haveNextNextGaussian = false
    }

    /** The next `bits` random bits (1–32), as `java.util.Random.next`. */
    private fun next(bits: Int): Int {
        state = (state * MULTIPLIER + ADDEND) and MASK
        return (state ushr (48 - bits)).toInt()
    }

    /** As `java.util.Random.nextInt()`. */
    fun nextInt(): Int = next(32)

    /** A number from 0 (included) to `bound` (excluded), as `java.util.Random.nextInt(int)`. */
    fun nextInt(bound: Int): Int {
        if (bound <= 0) throw IllegalArgumentException("bound must be positive")
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

    /**
     * As `java.util.Random.nextGaussian()`: Marsaglia's polar method, which draws two numbers and keeps the second for
     * the next call, with `StrictMath`'s logarithm ([StrictLog]) so the numbers are the same bit for bit.
     */
    fun nextGaussian(): Double {
        if (haveNextNextGaussian) {
            haveNextNextGaussian = false
            return nextNextGaussian
        }
        var v1: Double
        var v2: Double
        var s: Double
        do {
            v1 = 2 * nextDouble() - 1 // between -1 and 1
            v2 = 2 * nextDouble() - 1 // between -1 and 1
            s = v1 * v1 + v2 * v2
        } while (s >= 1 || s == 0.0)
        // sqrt is correctly rounded everywhere (IEEE 754), as StrictMath.sqrt
        val multiplier = kotlin.math.sqrt(-2 * StrictLog.log(s) / s)
        nextNextGaussian = v2 * multiplier
        haveNextNextGaussian = true
        return v1 * multiplier
    }

    companion object {
        private const val MULTIPLIER = 0x5DEECE66DL
        private const val ADDEND = 0xBL
        private const val MASK = (1L shl 48) - 1
        private const val DOUBLE_UNIT = 1.0 / (1L shl 53)

        private fun scramble(seed: Long): Long = (seed xor MULTIPLIER) and MASK

        /**
         * Shuffles exactly as `Collections.shuffle(list, random)` (from the last position down, each element swaps with
         * one at or before it), so a seed and the actions played reproduce a game anywhere.
         */
        @JvmStatic
        fun <T> shuffle(list: MutableList<T>, random: GameRandom?) {
            var i = list.size
            while (i > 1) {
                val j = random!!.nextInt(i)
                list[i - 1] = list.set(j, list[i - 1])
                i--
            }
        }
    }
}
