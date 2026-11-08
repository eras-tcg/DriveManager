package com.drive.drive_manager.simulator.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The core's Java patterns in JavaScript: translated, they match what Java's match (on both platforms). */
class JavaRegexTest {

    private fun js(pattern: String) = Regex(JavaRegex.toJavaScript(pattern))

    @Test
    fun whitespaceIsJavasSix() {
        assertTrue(js("nivel\\s*(\\d+)").containsMatchIn("nivel \t 3"))
        assertFalse(js("a\\sb").containsMatchIn("a b"), "a no-break space is not \\s in Java")
        assertTrue(js("a\\Sb").containsMatchIn("a b"))
        assertTrue(js("[\\s,]+").matches(" ,\n"))
    }

    @Test
    fun dotStopsAtJavasLineTerminators() {
        assertFalse(js("a.b").containsMatchIn("a\u0085b"))
        assertFalse(js("a.b").containsMatchIn("a\nb"))
        assertTrue(js("a.b").containsMatchIn("a b"))
    }

    @Test
    fun dollarAlsoBeforeAFinalLineTerminator() {
        val trailing = js("\\s*\\d+$")
        assertEquals(" 1", trailing.find("Letalidad 1")?.value)
        assertEquals(" 2", trailing.find("Letalidad 2\n")?.value)
        assertEquals(" 3", trailing.find("Letalidad 3\r\n")?.value)
        assertNull(trailing.find("Letalidad 4\n\n"))
        assertEquals("x", js("^(x)\\.?$").find("x.")?.groupValues?.get(1))
    }

    @Test
    fun keepsWhatIsTheSame() {
        assertEquals("\\|", JavaRegex.toJavaScript("\\|"))
        assertEquals("(?:a|b)(?!c)(?<!d)[\\[\\]]", JavaRegex.toJavaScript("(?:a|b)(?!c)(?<!d)[\\[\\]]"))
        assertEquals("[^.;]*?", JavaRegex.toJavaScript("[^.;]*?"))
    }

    @Test
    fun refusesWhatHasNoJavaScriptForm() {
        assertFailsWith<IllegalArgumentException> { JavaRegex.toJavaScript("(?i)protección") }
        assertFailsWith<IllegalArgumentException> { JavaRegex.toJavaScript("\\Qa.b\\E") }
    }
}
