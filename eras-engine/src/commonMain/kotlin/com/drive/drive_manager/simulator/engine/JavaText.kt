package com.drive.drive_manager.simulator.engine

import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmOverloads
/**
 * The few text functions of Java the engine relies on, written out in plain Kotlin with Java's exact results, where
 * Kotlin's look-alikes differ:
 * - [split]: `Pattern.split`/`String.split` drop the empty strings at the end (and give `[input]` when nothing matched);
 *   Kotlin's `split` keeps them.
 * - [isWhitespace]: `Character.isWhitespace` leaves out the no-break spaces; Kotlin's `Char.isWhitespace` takes them.
 * - [fixed]: `String.format("%.nf", x)` rounds the shortest decimal form half up (Java's Formatter), not the binary value.
 */
object JavaText {

    /** As `Regex(regex).split(input, limit)` (and `input.split(regex, limit)` in Java). */
    @JvmStatic
    @JvmOverloads
    fun split(regex: Regex, input: CharSequence, limit: Int = 0): Array<String> {
        var index = 0
        val matchLimited = limit > 0
        val matchList = ArrayList<String>()
        for (m in regex.findAll(input)) {
            val start = m.range.first
            val end = m.range.last + 1
            if (!matchLimited || matchList.size < limit - 1) {
                // no empty leading substring for a zero-width match at the beginning of the input
                if (index == 0 && start == 0 && start == end) continue
                matchList.add(input.subSequence(index, start).toString())
                index = end
            } else if (matchList.size == limit - 1) { // the last one
                matchList.add(input.subSequence(index, input.length).toString())
                index = end
            }
        }
        // no match found: the input
        if (index == 0) return arrayOf(input.toString())
        // the remaining segment
        if (!matchLimited || matchList.size < limit) matchList.add(input.subSequence(index, input.length).toString())
        var resultSize = matchList.size
        if (limit == 0) while (resultSize > 0 && matchList[resultSize - 1].isEmpty()) resultSize--
        return Array(resultSize) { matchList[it] }
    }

    /**
     * As `Character.isWhitespace(c)`: the Unicode space, line and paragraph separators but for the no-break spaces
     * (U+00A0, U+2007, U+202F), and the controls U+0009–U+000D and U+001C–U+001F.
     */
    @JvmStatic
    fun isWhitespace(c: Char): Boolean = c.isWhitespace() && c != ' ' && c != ' ' && c != ' '

    /** As Java's `s.isBlank()` (every char `Character.isWhitespace`), the engine's `s.all { Character.isWhitespace(it) }`. */
    @JvmStatic
    fun isBlank(s: CharSequence): Boolean = s.all { isWhitespace(it) }

    /**
     * As `String.format(Locale.ROOT, "%.<decimals>f", value)`: the shortest decimal form of the double (its
     * `toString()`'s digits) rounded half up to `decimals` places, the sign kept (`-0.0`), "NaN", "Infinity".
     */
    @JvmStatic
    fun fixed(value: Double, decimals: Int): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
        val negative = value < 0 || (value == 0.0 && 1.0 / value < 0)
        // The digits and the decimal exponent of the shortest form: value = 0.d1d2d3... × 10^point
        val text = (if (negative) -value else value).toString()
        val e = text.indexOfFirst { it == 'E' || it == 'e' }
        val mantissa = if (e < 0) text else text.substring(0, e)
        val exponent = if (e < 0) 0 else text.substring(e + 1).removePrefix("+").toInt()
        val dot = mantissa.indexOf('.')
        val intPart = if (dot < 0) mantissa else mantissa.substring(0, dot)
        val fracPart = if (dot < 0) "" else mantissa.substring(dot + 1)
        var digits = intPart + fracPart
        var point = intPart.length + exponent
        val lead = digits.indexOfFirst { it != '0' }
        if (lead < 0) {
            digits = "0"
            point = 1
        } else {
            digits = digits.substring(lead)
            point -= lead
        }
        // Keep `point + decimals` digits, half up on the next one
        val keep = point + decimals
        val kept: CharArray
        var newPoint = point
        if (keep < 0) {
            kept = CharArray(0)
        } else if (keep >= digits.length) {
            kept = (digits + "0".repeat(keep - digits.length)).toCharArray()
        } else {
            val roundUp = digits[keep] >= '5'
            val head = digits.substring(0, keep).toCharArray()
            if (roundUp) {
                var i = head.size - 1
                while (i >= 0 && head[i] == '9') {
                    head[i] = '0'
                    i--
                }
                if (i >= 0) {
                    head[i] = head[i] + 1
                    kept = head
                } else {
                    kept = CharArray(head.size + 1) { if (it == 0) '1' else head[it - 1] }
                    newPoint++
                }
            } else {
                kept = head
            }
        }
        // kept holds the digits from 10^(newPoint-1) down to 10^-decimals
        val all = kept.concatToString()
        val intDigits = newPoint
        val sb = StringBuilder()
        if (negative) sb.append('-')
        if (intDigits <= 0) {
            sb.append('0')
        } else {
            val ip = if (all.length >= intDigits) all.substring(0, intDigits) else all + "0".repeat(intDigits - all.length)
            sb.append(ip)
        }
        if (decimals > 0) {
            sb.append('.')
            val frac = StringBuilder()
            for (pos in 1..decimals) {
                // the digit at 10^-pos is at index intDigits + pos - 1 of all
                val idx = intDigits + pos - 1
                frac.append(if (idx >= 0 && idx < all.length) all[idx] else '0')
            }
            sb.append(frac)
        }
        return sb.toString()
    }
}
