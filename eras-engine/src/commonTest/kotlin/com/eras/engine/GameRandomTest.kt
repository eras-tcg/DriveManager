package com.eras.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The numbers java.util.Random gives for these seeds (taken from a JVM): the engine must give the very same on
 * every platform, or a replay would not play out the same in the browser and on the server.
 */
class GameRandomTest {
    private class Golden(
        val seed: Long,
        val nextInt: Int,
        val below40: Int,
        val below64: Int,
        val belowBig: Int,
        val nextLong: Long,
        val nextBoolean: Boolean,
        val nextDouble: Double,
        val shuffled: List<Int>,
    )

    private val golden = listOf(
        Golden(0L, -1155484576, 28, 15, 302116440, -6688467811848818630L, true, 0.11700660880722513,
            listOf(32, 24, 20, 1, 27, 8, 37, 12, 30, 34, 23, 22, 11, 13, 26, 25, 16, 2, 18, 3, 14, 9, 39, 4, 38, 29, 10, 21, 5, 17, 36, 33, 6, 19, 28, 35, 15, 7, 31, 0)),
        Golden(42L, -1170105035, 3, 43, 102948884, 5694868678511409995L, false, 0.7077105889551186,
            listOf(37, 4, 36, 26, 34, 5, 38, 1, 30, 35, 28, 24, 14, 19, 6, 31, 16, 8, 13, 23, 29, 11, 15, 27, 7, 20, 9, 0, 22, 32, 3, 21, 2, 17, 25, 18, 39, 12, 33, 10)),
        Golden(-7L, 1155869324, 17, 43, 119890471, 2701079913633722686L, false, 0.08574465212415472,
            listOf(16, 20, 15, 31, 27, 23, 24, 1, 19, 29, 13, 21, 35, 10, 32, 5, 34, 8, 37, 28, 4, 25, 14, 12, 26, 3, 39, 18, 11, 7, 6, 36, 38, 9, 17, 30, 2, 0, 33, 22)),
        Golden(1794067701123L, -184198148, 24, 45, 569026989, 9007361714316550782L, true, 0.6381770487049108,
            listOf(35, 29, 11, 13, 15, 5, 38, 0, 10, 32, 39, 3, 31, 33, 28, 16, 18, 8, 25, 19, 27, 7, 9, 20, 1, 23, 4, 21, 34, 2, 37, 24, 22, 36, 26, 17, 30, 6, 12, 14)),
        Golden(Long.MIN_VALUE, -1155484576, 28, 15, 302116440, -6688467811848818630L, true, 0.11700660880722513,
            listOf(32, 24, 20, 1, 27, 8, 37, 12, 30, 34, 23, 22, 11, 13, 26, 25, 16, 2, 18, 3, 14, 9, 39, 4, 38, 29, 10, 21, 5, 17, 36, 33, 6, 19, 28, 35, 15, 7, 31, 0)),
        Golden(Long.MAX_VALUE, 1155099827, 25, 0, 176895432, -6233441030884181172L, false, 0.3992884878242895,
            listOf(11, 30, 19, 2, 39, 5, 23, 14, 35, 16, 36, 6, 38, 20, 28, 27, 4, 13, 3, 17, 7, 24, 15, 21, 0, 29, 1, 9, 10, 34, 22, 26, 12, 25, 18, 32, 31, 37, 8, 33)),
    )

    @Test
    fun drawsTheNumbersOfJavaUtilRandom() {
        for (g in golden) {
            val r = GameRandom(g.seed)
            assertEquals(g.nextInt, r.nextInt(), "nextInt, seed ${g.seed}")
            assertEquals(g.below40, r.nextIntBelow(40), "nextInt(40), seed ${g.seed}")
            assertEquals(g.below64, r.nextIntBelow(64), "nextInt(64), seed ${g.seed}")
            assertEquals(g.belowBig, r.nextIntBelow(1_000_000_007), "nextInt(1e9+7), seed ${g.seed}")
            assertEquals(g.nextLong, r.nextLong(), "nextLong, seed ${g.seed}")
            assertEquals(g.nextBoolean, r.nextBoolean(), "nextBoolean, seed ${g.seed}")
            assertEquals(g.nextDouble, r.nextDouble(), "nextDouble, seed ${g.seed}")
        }
    }

    @Test
    fun shufflesAsCollectionsShuffle() {
        for (g in golden) {
            val deck = MutableList(40) { it }
            deck.shuffleAs(GameRandom(g.seed))
            assertEquals(g.shuffled, deck, "shuffle, seed ${g.seed}")
        }
    }

    @Test
    fun setSeedStartsOver() {
        val r = GameRandom(1)
        val first = r.nextLong()
        r.nextLong()
        r.setSeed(1)
        assertEquals(first, r.nextLong())
    }
}
