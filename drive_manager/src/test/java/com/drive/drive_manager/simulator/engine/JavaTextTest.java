package com.drive.drive_manager.simulator.engine;

import kotlin.text.Regex;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** JavaText gives exactly what Java's Pattern.split, Character.isWhitespace and String.format("%.nf") give. */
class JavaTextTest {

    @Test
    void splitsAsPatternSplit() {
        String[] regexes = {",", "\\|", "=", "\\s*", "x*", "\\R", ",+", "(?=b)", "^", "$"};
        String[] inputs = {"", ",", ",,", "a", "a,", ",a", "a,,b,,", "a|b|c|", "|a||b", "k=v=w", " a  b ", "abcb", "a\r\nb\n\nc ",
                "xaxxbx", "bbb", "a,b,c,,,"};
        for (String regex : regexes) for (String input : inputs) for (int limit : new int[] {0, -1, 1, 2, 3, 5}) {
            assertThat(JavaText.split(new Regex(regex), input, limit))
                    .as("split(/%s/, \"%s\", %d)", regex, input, limit)
                    .containsExactly(Pattern.compile(regex).split(input, limit));
        }
        assertThat(JavaText.split(new Regex(","), "a,b,,")).containsExactly("a,b,,".split(","));
    }

    @Test
    void takesWhitespaceAsCharacterIsWhitespace() {
        for (int c = 0; c <= 0xFFFF; c++) {
            assertThat(JavaText.isWhitespace((char) c)).as("U+%04X", c).isEqualTo(Character.isWhitespace((char) c));
        }
        assertThat(JavaText.isBlank(" \t\n")).isTrue();
        assertThat(JavaText.isBlank(" ")).isFalse();
        assertThat(JavaText.isBlank("")).isTrue();
    }

    @Test
    void formatsAsStringFormat() {
        double[] edges = {0.0, -0.0, 0.05, 0.15, 0.25, 0.35, 0.45, -0.05, -0.04, 9.95, 9.96, 99.95, 0.95, 1.005, 2.675,
                1e7, 1.25e7, 123456789.05, 1e-3, 1e-4, 5e-5, 4.9e-324, Double.MIN_NORMAL, 1e22, 1e23, Double.MAX_VALUE,
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1234.5, 0.5, 1.5, 2.5};
        for (double x : edges) for (int d = 0; d <= 3; d++) check(x, d);
        Random r = new Random(11);
        for (int i = 0; i < 300_000; i++) {
            double x = switch (i % 5) {
                case 0 -> (r.nextDouble() - 0.5) * 2000;                    // scores and means
                case 1 -> Math.round(r.nextDouble() * 100000) / 1000.0;     // ties on the last kept place
                case 2 -> r.nextInt(2000) / 20.0 - 50;                       // x.x5 exactly representable or not
                case 3 -> (r.nextDouble() - 0.5) * Math.pow(10, r.nextInt(30) - 10);
                default -> Double.longBitsToDouble(r.nextLong());
            };
            check(x, 1 + i % 3);
        }
    }

    private static void check(double x, int decimals) {
        String ours = JavaText.fixed(x, decimals);
        String java = String.format(Locale.ROOT, "%." + decimals + "f", x);
        if (!ours.equals(java)) assertThat(ours).as("%%.%df of %s", decimals, x).isEqualTo(java);
    }
}
