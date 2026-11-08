package com.drive.drive_manager.simulator.platform

/**
 * Java's regular expressions (`java.util.regex.Pattern`, what the core's patterns were written for) in JavaScript's syntax
 * (`RegExp` with the flags Kotlin gives it, "gu"), with Java's meaning where the two differ for what the core writes:
 *
 * - `\s` / `\S`: Java's six whitespace characters `[ \t\n\x0B\f\r]` (JavaScript adds the Unicode spaces, such as a no-break
 *   space);
 * - `.`: any character but Java's line terminators `\n`, `\r`, `\u0085`, ` `, ` ` (JavaScript lets `\u0085` through);
 * - `$`: the end, or before a line terminator that ends the input (JavaScript: only the end).
 *
 * Inline flags (`(?i)`…) and `\Q…\E` have no JavaScript form: refused, so a pattern that needs them is rewritten in the core.
 */
object JavaRegex {

    private const val SPACES = " \\t\\n\\u000B\\f\\r"
    private const val TERMINATORS = "\\n\\r\\u0085\\u2028\\u2029"

    fun toJavaScript(pattern: String): String {
        val out = StringBuilder()
        var inClass = false
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> {
                    require(i + 1 < pattern.length) { "Trailing backslash: $pattern" }
                    val e = pattern[i + 1]
                    when {
                        e == 's' -> out.append(if (inClass) SPACES else "[$SPACES]")
                        e == 'S' && !inClass -> out.append("[^$SPACES]")
                        e == 'S' || e == 'Q' || e == 'E' -> throw IllegalArgumentException("No JavaScript form for \\$e here: $pattern")
                        else -> out.append(c).append(e)
                    }
                    i += 2
                    continue
                }
                inClass -> {
                    if (c == '[') throw IllegalArgumentException("No JavaScript form for a class inside a class: $pattern")
                    if (c == ']') inClass = false
                    out.append(c)
                }
                c == '[' -> {
                    inClass = true
                    out.append(c)
                    // A ']' right after '[' or '[^' is a character of the class in Java (an error in JavaScript's u mode)
                    if (pattern.startsWith("^", i + 1)) { out.append('^'); i++ }
                    if (pattern.startsWith("]", i + 1)) { out.append("\\]"); i++ }
                }
                c == '.' -> out.append("[^$TERMINATORS]")
                c == '$' -> out.append("(?=(?:\\r\\n|[$TERMINATORS])?(?![\\s\\S]))")
                c == '(' && pattern.startsWith("(?", i) && i + 2 < pattern.length && pattern[i + 2] !in ":=!<" ->
                    throw IllegalArgumentException("No JavaScript form for inline flags: $pattern")
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
