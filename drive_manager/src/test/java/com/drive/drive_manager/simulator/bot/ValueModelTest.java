package com.drive.drive_manager.simulator.bot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** The bot's value model learns who wins from the table, is saved and read back the same, and the promotion criteria. */
class ValueModelTest {

    private static final int LIFE_DIFF = BoardFeatures.NAMES.indexOf("lifeDiff");

    /** Tables where the side with more lives usually wins (90 %), the other numbers noise. */
    private static List<ValueModel.Sample> samples(int n, long seed) {
        Random r = new Random(seed);
        List<ValueModel.Sample> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double[] x = new double[BoardFeatures.NAMES.size()];
            for (int j = 0; j < x.length; j++) x[j] = r.nextGaussian() * 3;
            boolean ahead = x[LIFE_DIFF] > 0;
            out.add(new ValueModel.Sample(x, r.nextDouble() < 0.9 == ahead ? 1 : 0));
        }
        return out;
    }

    @Test
    void itLearnsWhoWinsFromTheTable() {
        ValueModel model = ValueModel.train(samples(4000, 1), ValueModel.Training.DEFAULT, null);
        ValueModel.Fit fit = model.fit(samples(1000, 2));
        // The best possible is 0.9 (one label in ten is flipped), with 29 numbers of noise to see through
        assertThat(fit.accuracy()).isGreaterThan(0.8);
        assertThat(fit.logLoss()).isLessThan(Math.log(2));
        double[] ahead = new double[BoardFeatures.NAMES.size()];
        ahead[LIFE_DIFF] = 4;
        double[] behind = ahead.clone();
        behind[LIFE_DIFF] = -4;
        assertThat(model.predict(ahead)).isGreaterThan(0.6);
        assertThat(model.predict(behind)).isLessThan(0.4);
    }

    @Test
    void aLogisticRegressionLearnsTooAndItIsSavedAndReadTheSame(@TempDir Path dir) throws Exception {
        ValueModel.Training linear = new ValueModel.Training(0, 30, 256, 0.01, 1e-4, 0.15, 5, 1);
        ValueModel model = ValueModel.train(samples(3000, 3), linear, null);
        assertThat(model.fit(samples(500, 4)).accuracy()).isGreaterThan(0.8);
        Path file = dir.resolve("model.json");
        model.save(file);
        ValueModel read = ValueModel.read(file);
        for (ValueModel.Sample s : samples(20, 5)) assertThat(read.predict(s.x())).isCloseTo(model.predict(s.x()), org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void aCandidateIsPromotedOnlyWhenItIsSurelyBetterAndPlaysClean() {
        BotTrainingRun.Criteria c = new BotTrainingRun.Criteria(0.5, 0, 0.05, 2);
        assertThat(BotTrainingRun.decide(c, match(0.56, 0, 0, 0.01))).isNull();
        assertThat(BotTrainingRun.decide(c, match(0.48, 0, 0, 0.01))).contains("95 %");
        assertThat(BotTrainingRun.decide(c, match(0.6, 1, 0, 0.01))).contains("conceded");
        assertThat(BotTrainingRun.decide(c, match(0.6, 0, 2, 0.01))).contains("stuck");
        assertThat(BotTrainingRun.decide(c, match(0.6, 0, 0, 0.2))).contains("time");
    }

    private static Map<String, Object> match(double low, long conceded, long stuck, double outOfTime) {
        return Map.of("winLow95", low, "conceded", conceded, "stuck", stuck, "outOfTimeShare", outOfTime);
    }
}
