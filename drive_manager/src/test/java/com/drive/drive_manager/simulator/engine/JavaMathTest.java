package com.drive.drive_manager.simulator.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.DoubleStream;

import static org.assertj.core.api.Assertions.assertThat;

/** JavaMath gives exactly what Math.round(double), DoubleStream.sum() and DoubleStream.average() give. */
class JavaMathTest {

    @Test
    void roundsAsMathRound() {
        double[] edges = {0.0, -0.0, 0.5, -0.5, 1.5, -1.5, 2.5, -2.5, 0.49999999999999994, -0.49999999999999994, 4503599627370495.5,
                4503599627370496.0, 9007199254740993.0, 1e300, -1e300, Long.MAX_VALUE, Long.MIN_VALUE, Double.MIN_VALUE,
                Double.MAX_VALUE, -Double.MAX_VALUE, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.1, 2.675};
        for (double d : edges) assertThat(JavaMath.round(d)).as("round(%s)", d).isEqualTo(Math.round(d));
        Random random = new Random(7);
        for (int i = 0; i < 200_000; i++) {
            double d = switch (i % 4) {
                case 0 -> random.nextDouble() * 100 - 50;
                case 1 -> Math.floor(random.nextDouble() * 2000 - 1000) + 0.5;
                case 2 -> Double.longBitsToDouble(random.nextLong());
                default -> random.nextGaussian() * 1e6;
            };
            assertThat(JavaMath.round(d)).as("round(%s)", d).isEqualTo(Math.round(d));
        }
    }

    @Test
    void sumsAndAveragesAsDoubleStream() {
        Random random = new Random(11);
        for (int run = 0; run < 2_000; run++) {
            int n = random.nextInt(40);
            List<Double> values = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                values.add(switch (random.nextInt(5)) {
                    case 0 -> random.nextDouble();
                    case 1 -> random.nextGaussian() * 1e12;
                    case 2 -> 1e-9 * random.nextDouble();
                    case 3 -> (double) random.nextInt(100);
                    default -> -random.nextDouble() * 1e5;
                });
            }
            double[] raw = values.stream().mapToDouble(Double::doubleValue).toArray();
            assertThat(JavaMath.INSTANCE.sum(values, d -> d)).isEqualTo(DoubleStream.of(raw).sum());
            Double average = JavaMath.INSTANCE.average(values, d -> d);
            if (n == 0) assertThat(average).isNull();
            else assertThat(average).isEqualTo(DoubleStream.of(raw).average().getAsDouble());
        }
        // Infinities: the simple sum when the compensated one is NaN
        List<Double> infinite = List.of(1.0, Double.POSITIVE_INFINITY, 2.0);
        assertThat(JavaMath.INSTANCE.sum(infinite, d -> d)).isEqualTo(DoubleStream.of(1.0, Double.POSITIVE_INFINITY, 2.0).sum());
        List<Double> both = List.of(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
        assertThat(JavaMath.INSTANCE.sum(both, d -> d)).isNaN();
        assertThat(DoubleStream.of(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).sum()).isNaN();
    }
}
