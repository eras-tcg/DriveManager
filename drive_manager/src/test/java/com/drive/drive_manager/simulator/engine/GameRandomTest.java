package com.drive.drive_manager.simulator.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GameRandom draws exactly the numbers of java.util.Random from the same seed, and GameRandom.shuffle shuffles exactly
 * as Collections.shuffle, on random-access lists and linked ones.
 */
class GameRandomTest {

    @Test
    void shufflesAsCollectionsShuffle() {
        for (long seed = -20; seed <= 20; seed++) for (int size : new int[] {0, 1, 2, 4, 5, 6, 40, 61}) {
            List<Integer> java = new ArrayList<>(IntStream.range(0, size).boxed().toList());
            Collections.shuffle(java, new Random(seed));
            List<Integer> ours = new ArrayList<>(IntStream.range(0, size).boxed().toList());
            GameRandom.shuffle(ours, new GameRandom(seed));
            assertThat(ours).as("seed %d, size %d", seed, size).isEqualTo(java);
            List<Integer> linked = new LinkedList<>(IntStream.range(0, size).boxed().toList());
            GameRandom.shuffle(linked, new GameRandom(seed));
            assertThat(linked).as("linked, seed %d, size %d", seed, size).isEqualTo(java);
        }
    }

    @Test
    void drawsAsJavaUtilRandom() {
        Random seeds = new Random(2026);
        for (int round = 0; round < 300; round++) {
            long seed = round < 40 ? round - 20 : round < 45 ? new long[] {Long.MIN_VALUE, Long.MAX_VALUE, 0x5DEECE66DL, -1L << 48, 1L << 47}[round - 40] : seeds.nextLong();
            Random java = new Random(seed);
            GameRandom ours = new GameRandom(seed);
            for (int i = 0; i < 400; i++) {
                switch (i % 9) {
                    case 0 -> assertThat(ours.nextInt()).as("seed %d, draw %d", seed, i).isEqualTo(java.nextInt());
                    case 1 -> {
                        int bound = 1 + Math.floorMod(java.nextInt() ^ (int) seed, 1 << (1 + i % 30));
                        ours.nextInt();
                        assertThat(ours.nextInt(bound)).as("seed %d, draw %d, bound %d", seed, i, bound).isEqualTo(java.nextInt(bound));
                    }
                    case 2 -> assertThat(ours.nextLong()).as("seed %d, draw %d", seed, i).isEqualTo(java.nextLong());
                    case 3 -> assertThat(ours.nextBoolean()).as("seed %d, draw %d", seed, i).isEqualTo(java.nextBoolean());
                    case 4 -> assertThat(Double.doubleToRawLongBits(ours.nextDouble())).as("seed %d, draw %d", seed, i)
                            .isEqualTo(Double.doubleToRawLongBits(java.nextDouble()));
                    case 5, 6 -> assertThat(Double.doubleToRawLongBits(ours.nextGaussian())).as("gaussian, seed %d, draw %d", seed, i)
                            .isEqualTo(Double.doubleToRawLongBits(java.nextGaussian()));
                    case 7 -> {
                        // bounds that are powers of two, near Integer.MAX_VALUE (the rejection loop), and small ones
                        for (int bound : new int[] {1, 2, 3, 7, 64, 100, 1 << 30, (1 << 30) + 1, Integer.MAX_VALUE}) {
                            assertThat(ours.nextInt(bound)).as("seed %d, draw %d, bound %d", seed, i, bound).isEqualTo(java.nextInt(bound));
                        }
                    }
                    default -> {
                        // a reseed forgets the kept second gaussian, as java.util.Random's
                        if (i % 90 == 8) {
                            java.setSeed(seed + i);
                            ours.setSeed(seed + i);
                        }
                        assertThat(Double.doubleToRawLongBits(ours.nextGaussian())).as("gaussian, seed %d, draw %d", seed, i)
                                .isEqualTo(Double.doubleToRawLongBits(java.nextGaussian()));
                    }
                }
            }
        }
    }

    @Test
    void refusesABoundBelowOneAsJavaUtilRandom() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new GameRandom(1).nextInt(0)).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Random(1).nextInt(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void takesTheLogarithmAsStrictMath() {
        Random r = new Random(7);
        double[] edges = {0.0, -0.0, 1.0, -1.0, Double.MIN_VALUE, Double.MIN_NORMAL, Double.MAX_VALUE, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Math.E, 2.0, 0.5, 1 + 1e-7, 1 - 1e-7, 1 + Math.ulp(1.0),
                1 - Math.ulp(1.0) / 2, 1e-310, 4.9e-324, 1e308};
        for (double x : edges) assertThat(Double.doubleToRawLongBits(StrictLog.log(x))).as("log(%s)", x)
                .isEqualTo(Double.doubleToRawLongBits(StrictMath.log(x)));
        for (int i = 0; i < 2_000_000; i++) {
            double x = switch (i % 4) {
                case 0 -> r.nextDouble();                                   // the polar method's s, in (0, 1)
                case 1 -> Double.longBitsToDouble(r.nextLong() & Long.MAX_VALUE); // any positive double
                case 2 -> 1 + (r.nextDouble() - 0.5) * 1e-5;                // near 1
                default -> r.nextDouble() * 1e-300;                         // tiny and subnormal
            };
            long ours = Double.doubleToRawLongBits(StrictLog.log(x));
            long strict = Double.doubleToRawLongBits(StrictMath.log(x));
            if (ours != strict) assertThat(ours).as("log(%s)", x).isEqualTo(strict);
        }
    }
}
