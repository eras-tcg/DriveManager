package com.eras.engine

import java.util.Collections
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** On the JVM, against java.util.Random itself: many seeds, many draws, every bound kind. */
class JavaRandomParityTest {
    private val seeds = (-50L..50L) + listOf(Long.MIN_VALUE, Long.MAX_VALUE, 1794067701123L, 0x5DEECE66DL)
    private val bounds = intArrayOf(1, 2, 3, 7, 16, 40, 60, 64, 1000, 1 shl 30, Int.MAX_VALUE)

    @Test
    fun everyDrawMatches() {
        for (seed in seeds) {
            val java = Random(seed)
            val ours = GameRandom(seed)
            repeat(1000) { i ->
                when (i % 5) {
                    0 -> assertEquals(java.nextInt(), ours.nextInt())
                    1 -> bounds[i % bounds.size].let { assertEquals(java.nextInt(it), ours.nextIntBelow(it)) }
                    2 -> assertEquals(java.nextLong(), ours.nextLong())
                    3 -> assertEquals(java.nextBoolean(), ours.nextBoolean())
                    else -> assertEquals(java.nextDouble(), ours.nextDouble())
                }
            }
        }
    }

    @Test
    fun shuffleMatchesCollectionsShuffle() {
        for (seed in seeds) for (size in listOf(0, 1, 2, 5, 40, 60)) {
            val java = MutableList(size) { it }.also { Collections.shuffle(it, Random(seed)) }
            val ours = MutableList(size) { it }.also { it.shuffleAs(GameRandom(seed)) }
            assertEquals(java, ours, "seed $seed, size $size")
        }
    }
}
