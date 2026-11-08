package com.drive.drive_manager.simulator.engine

/**
 * The natural logarithm exactly as `java.lang.StrictMath.log`: fdlibm's `__ieee754_log` (e_log.c), which the JDK
 * runs as Java code (`FdLibm.Log`). Plain double arithmetic, so it gives the same bits on every platform, where
 * `kotlin.math.ln` (`Math.log`, the JavaScript `Math.log`) may differ in the last bit. [GameRandom.nextGaussian] needs it.
 */
object StrictLog {

    private val LN2_HI = Double.fromBits(0x3fe62e42fee00000L) // 6.93147180369123816490e-01
    private val LN2_LO = Double.fromBits(0x3dea39ef35793c76L) // 1.90821492927058770002e-10
    private val TWO54 = Double.fromBits(0x4350000000000000L) // 1.80143985094819840000e+16
    private val LG1 = Double.fromBits(0x3fe5555555555593L) // 6.666666666666735130e-01
    private val LG2 = Double.fromBits(0x3fd999999997fa04L) // 3.999999999940941908e-01
    private val LG3 = Double.fromBits(0x3fd2492494229359L) // 2.857142874366239149e-01
    private val LG4 = Double.fromBits(0x3fcc71c51d8e78afL) // 2.222219843214978396e-01
    private val LG5 = Double.fromBits(0x3fc7466496cb03deL) // 1.818357216161805012e-01
    private val LG6 = Double.fromBits(0x3fc39a09d078c69fL) // 1.531383769920937332e-01
    private val LG7 = Double.fromBits(0x3fc2f112df3e5244L) // 1.479819860511658591e-01

    private fun hi(x: Double): Int = (x.toRawBits() ushr 32).toInt()
    private fun lo(x: Double): Int = x.toRawBits().toInt()
    private fun withHi(x: Double, high: Int): Double =
        Double.fromBits((high.toLong() shl 32) or (x.toRawBits() and 0xffffffffL))

    @JvmStatic
    fun log(input: Double): Double {
        var x = input
        var hx = hi(x)
        val lx = lo(x)
        var k = 0
        if (hx < 0x00100000) { // x < 2**-1022
            if (((hx and 0x7fffffff) or lx) == 0) return -TWO54 / 0.0 // log(+-0) = -inf
            if (hx < 0) return (x - x) / 0.0 // log(-#) = NaN
            k -= 54
            x *= TWO54 // subnormal number, scale up x
            hx = hi(x)
        }
        if (hx >= 0x7ff00000) return x + x
        k += (hx shr 20) - 1023
        hx = hx and 0x000fffff
        var i = (hx + 0x95f64) and 0x100000
        x = withHi(x, hx or (i xor 0x3ff00000)) // normalize x or x/2
        k += i shr 20
        val f = x - 1.0
        if ((0x000fffff and (2 + hx)) < 3) { // -2**-20 <= f < 2**-20
            if (f == 0.0) {
                if (k == 0) return 0.0
                val dk = k.toDouble()
                return dk * LN2_HI + dk * LN2_LO
            }
            val r = f * f * (0.5 - 0.33333333333333333 * f)
            if (k == 0) return f - r
            val dk = k.toDouble()
            return dk * LN2_HI - ((r - dk * LN2_LO) - f)
        }
        val s = f / (2.0 + f)
        val dk = k.toDouble()
        val z = s * s
        i = hx - 0x6147a
        val w = z * z
        val j = 0x6b851 - hx
        val t1 = w * (LG2 + w * (LG4 + w * LG6))
        val t2 = z * (LG1 + w * (LG3 + w * (LG5 + w * LG7)))
        i = i or j
        val r = t2 + t1
        return if (i > 0) {
            val hfsq = 0.5 * f * f
            if (k == 0) f - (hfsq - s * (hfsq + r))
            else dk * LN2_HI - ((hfsq - (s * (hfsq + r) + dk * LN2_LO)) - f)
        } else {
            if (k == 0) f - s * (f - r)
            else dk * LN2_HI - ((s * (f - r) - dk * LN2_LO) - f)
        }
    }
}
