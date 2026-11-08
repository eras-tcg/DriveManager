package com.drive.drive_manager.simulator.engine

/**
 * The few numeric functions of Java the engine relies on, written out in plain Kotlin with Java's exact results, where
 * Kotlin's look-alikes differ:
 * - [round]: `Math.round(double)` gives 0 for NaN (Kotlin's `roundToLong` throws) and rounds half up exactly (a
 *   JavaScript `floor(x + 0.5)` rounds 0.49999999999999994 up);
 * - [sum] and [average]: `DoubleStream.sum()`/`average()` add with compensation (Kahan); Kotlin's `sum()`/`average()` add
 *   plainly, which can differ in the last bits and move a bot's choice.
 */
object JavaMath {

    /** As `Math.round(double)`: the closest long, ties towards positive infinity; NaN is 0, beyond the range saturates. */
    @JvmStatic
    fun round(a: Double): Long {
        val longBits = a.toRawBits()
        val biasedExp = (longBits and 0x7FF0000000000000L) shr 52
        val shift = (53 - 2 + 1023) - biasedExp
        if ((shift and -64L) == 0L) { // shift >= 0 && shift < 64
            // a is a finite number such that pow(2,-64) <= ulp(a) < 1
            var r = (longBits and 0x000FFFFFFFFFFFFFL) or 0x0010000000000000L
            if (longBits < 0) r = -r
            // (r) is a / ulp(a), (r >> shift) is floor(a * 2), ((r >> shift) + 1) >> 1 is floor(a + 1/2)
            return ((r shr shift.toInt()) + 1) shr 1
        }
        // a is either a finite number with abs(a) < 1/2, or a mathematical integer, or an infinity or NaN
        return a.toLong()
    }

    /** As `DoubleStream.sum()` (sequential): Kahan summation, the simple sum when the compensated one is NaN from infinities. */
    inline fun <T> sum(items: Iterable<T>, value: (T) -> Double): Double {
        val sums = DoubleArray(3)
        for (item in items) {
            val d = value(item)
            sumWithCompensation(sums, d)
            sums[2] += d
        }
        return computeFinalSum(sums[0], sums[1], sums[2])
    }

    /** As `DoubleStream.average()` (sequential): null for no items, else the compensated sum over the count. */
    inline fun <T> average(items: Iterable<T>, value: (T) -> Double): Double? {
        val sums = DoubleArray(3)
        var count = 0L
        for (item in items) {
            val d = value(item)
            count++
            sumWithCompensation(sums, d)
            sums[2] += d
        }
        return if (count > 0) computeFinalSum(sums[0], sums[1], sums[2]) / count else null
    }

    /** `Collectors.sumWithCompensation`: `sums[0]` the sum, `sums[1]` the compensation (negated low bits). */
    @PublishedApi
    internal fun sumWithCompensation(sums: DoubleArray, value: Double) {
        val tmp = value - sums[1]
        val sum = sums[0]
        val velvel = sum + tmp // Little wolf of rounding error
        sums[1] = (velvel - sum) - tmp
        sums[0] = velvel
    }

    /** `Collectors.computeFinalSum`: the compensated sum, or the simple one when that is NaN from infinities. */
    @PublishedApi
    internal fun computeFinalSum(sum: Double, compensation: Double, simpleSum: Double): Double {
        val tmp = sum - compensation
        return if (tmp.isNaN() && simpleSum.isInfinite()) simpleSum else tmp
    }
}

/** As `Comparator.comparingDouble(key)`: by the key, as `Double.compare` (NaN last, -0.0 before 0.0), without boxing. */
inline fun <T> comparingDouble(crossinline key: (T) -> Double): Comparator<T> =
    Comparator { a, b -> key(a).compareTo(key(b)) }

/** As `Comparator.comparingInt(key)`: by the key, without boxing. */
inline fun <T> comparingInt(crossinline key: (T) -> Int): Comparator<T> =
    Comparator { a, b -> key(a).compareTo(key(b)) }
