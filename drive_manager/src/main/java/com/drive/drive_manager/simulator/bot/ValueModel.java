package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.GameRandom;
import com.drive.drive_manager.simulator.room.GameRoom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A trainable model of the table's worth: the chance that a player wins from it, read from the table's numbers
 * ({@link BoardFeatures}). A small neural network: the numbers are standardized, go through one hidden layer
 * (ReLU) and a sigmoid; with no hidden units it is a logistic regression.
 * <p>
 * It learns from games already played ({@link #train}): each sample is a table seen by one player and whether
 * that player won; whole games are kept for validation. What it minimizes is the log loss (binary cross-entropy) plus an L2 penalty on its weights,
 * by Adam on mini-batches; it keeps the weights of the epoch with the lowest log loss on the validation samples
 * and stops when that has not improved for {@code patience} epochs. Saved and read as JSON by {@link ValueModelFiles}.
 */
public final class ValueModel {


    /** The model as it is saved: its weights, how its input is standardized, and how it was trained. */
    public record Weights(List<String> features, double[] mean, double[] std, int hidden,
                          double[][] w1, double[] b1, double[] w2, double b2, Map<String, Object> training) {}

    /** How it is trained. {@code validation}: share of the samples kept apart to judge each epoch. */
    public record Training(int hidden, int epochs, int batch, double learningRate, double l2, double validation, int patience, long seed) {
        public static final Training DEFAULT = new Training(16, 60, 256, 0.003, 1e-4, 0.15, 8, 1);
    }

    /**
     * A table seen by a player, whether that player won it (1) or lost it (0), and the game it comes from (the tables
     * of one game look alike, so a game is kept whole on one side of the training / validation split).
     */
    public record Sample(double[] x, double won, long game) {
        public Sample(double[] x, double won) {
            this(x, won, -1);
        }
    }

    /** How well it predicts: log loss, Brier score and accuracy (predicting a win at 0.5 or more). */
    public record Fit(double logLoss, double brier, double accuracy, int samples) {}

    private final Weights w;

    private ValueModel(Weights weights) {
        this.w = weights;
    }

    public Weights weights() {
        return w;
    }

    // ------------------------------------------------------------------ Using it

    /** The chance that {@code me} wins from the table. */
    double winChance(GameRoom room, String me) {
        return predict(BoardFeatures.of(room, me));
    }

    double predict(double[] x) {
        return sigmoid(logit(x, null));
    }

    /** The output before the sigmoid; {@code hiddenOut} (when not null) receives the hidden layer, for training. */
    private double logit(double[] x, double[] hiddenOut) {
        int n = x.length;
        double[] z = new double[n];
        for (int i = 0; i < n; i++) z[i] = (x[i] - w.mean()[i]) / w.std()[i];
        if (w.hidden() == 0) {
            double s = w.b2();
            for (int i = 0; i < n; i++) s += w.w2()[i] * z[i];
            return s;
        }
        double s = w.b2();
        for (int h = 0; h < w.hidden(); h++) {
            double a = w.b1()[h];
            for (int i = 0; i < n; i++) a += w.w1()[h][i] * z[i];
            a = Math.max(0, a);
            if (hiddenOut != null) hiddenOut[h] = a;
            s += w.w2()[h] * a;
        }
        return s;
    }

    // ------------------------------------------------------------------ Training

    /**
     * A model trained on the samples (see the class). {@code log} receives one line per epoch.
     */
    public static ValueModel train(List<Sample> samples, Training t, java.util.function.Consumer<String> log) {
        if (samples.size() < 10) throw new IllegalArgumentException("Too few samples to train: " + samples.size());
        Random random = new Random(t.seed());
        // Whole games to validation (a sample without a game is a game of its own)
        Map<Long, List<Sample>> byGame = new LinkedHashMap<>();
        long alone = Long.MIN_VALUE;
        for (Sample s : samples) byGame.computeIfAbsent(s.game() >= 0 ? s.game() : alone++, k -> new ArrayList<>()).add(s);
        List<List<Sample>> games = new ArrayList<>(byGame.values());
        GameRandom.shuffle(games, random);
        List<Sample> train = new ArrayList<>();
        List<Sample> valid = new ArrayList<>();
        for (List<Sample> g : games) (valid.size() < samples.size() * t.validation() ? valid : train).addAll(g);
        int n = BoardFeatures.NAMES.size();

        double[] mean = new double[n];
        double[] std = new double[n];
        for (Sample s : train) for (int i = 0; i < n; i++) mean[i] += s.x()[i] / train.size();
        for (Sample s : train) for (int i = 0; i < n; i++) std[i] += Math.pow(s.x()[i] - mean[i], 2) / train.size();
        for (int i = 0; i < n; i++) std[i] = Math.max(1e-6, Math.sqrt(std[i]));

        int hidden = t.hidden();
        int width = hidden == 0 ? n : hidden;
        double[][] w1 = new double[hidden][n];
        double[] b1 = new double[hidden];
        double[] w2 = new double[width];
        for (int h = 0; h < hidden; h++) for (int i = 0; i < n; i++) w1[h][i] = random.nextGaussian() * Math.sqrt(2.0 / n);
        for (int h = 0; h < width; h++) w2[h] = random.nextGaussian() * Math.sqrt(1.0 / width) * 0.1;
        double[] b2 = {0};

        // Adam moments, one per weight, in the same shapes
        double[][] m1 = new double[hidden][n], v1 = new double[hidden][n];
        double[] mb1 = new double[hidden], vb1 = new double[hidden], m2 = new double[width], v2 = new double[width];
        double[] mb2 = {0}, vb2 = {0};
        double beta1 = 0.9, beta2 = 0.999, eps = 1e-8;
        long step = 0;

        Weights best = null;
        double bestLoss = Double.POSITIVE_INFINITY;
        int sinceBest = 0;
        List<Map<String, Object>> epochs = new ArrayList<>();
        for (int epoch = 1; epoch <= t.epochs(); epoch++) {
            List<Sample> order = new ArrayList<>(train);
            GameRandom.shuffle(order, random);
            for (int start = 0; start < order.size(); start += t.batch()) {
                List<Sample> batch = order.subList(start, Math.min(order.size(), start + t.batch()));
                double[][] g1 = new double[hidden][n];
                double[] gb1 = new double[hidden], g2 = new double[width];
                double gb2 = 0;
                ValueModel current = new ValueModel(new Weights(BoardFeatures.NAMES, mean, std, hidden, w1, b1, w2, b2[0], null));
                double[] act = new double[hidden];
                for (Sample s : batch) {
                    double p = sigmoid(current.logit(s.x(), act));
                    double d = (p - s.won()) / batch.size();  // d(log loss)/d(logit)
                    double[] z = new double[n];
                    for (int i = 0; i < n; i++) z[i] = (s.x()[i] - mean[i]) / std[i];
                    gb2 += d;
                    if (hidden == 0) {
                        for (int i = 0; i < n; i++) g2[i] += d * z[i];
                        continue;
                    }
                    for (int h = 0; h < hidden; h++) {
                        g2[h] += d * act[h];
                        if (act[h] <= 0) continue;
                        double dh = d * w2[h];
                        gb1[h] += dh;
                        for (int i = 0; i < n; i++) g1[h][i] += dh * z[i];
                    }
                }
                step++;
                double c1 = 1 - Math.pow(beta1, step), c2 = 1 - Math.pow(beta2, step);
                for (int h = 0; h < hidden; h++) {
                    for (int i = 0; i < n; i++) {
                        double g = g1[h][i] + t.l2() * w1[h][i];
                        m1[h][i] = beta1 * m1[h][i] + (1 - beta1) * g;
                        v1[h][i] = beta2 * v1[h][i] + (1 - beta2) * g * g;
                        w1[h][i] -= t.learningRate() * (m1[h][i] / c1) / (Math.sqrt(v1[h][i] / c2) + eps);
                    }
                    mb1[h] = beta1 * mb1[h] + (1 - beta1) * gb1[h];
                    vb1[h] = beta2 * vb1[h] + (1 - beta2) * gb1[h] * gb1[h];
                    b1[h] -= t.learningRate() * (mb1[h] / c1) / (Math.sqrt(vb1[h] / c2) + eps);
                }
                for (int h = 0; h < width; h++) {
                    double g = g2[h] + t.l2() * w2[h];
                    m2[h] = beta1 * m2[h] + (1 - beta1) * g;
                    v2[h] = beta2 * v2[h] + (1 - beta2) * g * g;
                    w2[h] -= t.learningRate() * (m2[h] / c1) / (Math.sqrt(v2[h] / c2) + eps);
                }
                mb2[0] = beta1 * mb2[0] + (1 - beta1) * gb2;
                vb2[0] = beta2 * vb2[0] + (1 - beta2) * gb2 * gb2;
                b2[0] -= t.learningRate() * (mb2[0] / c1) / (Math.sqrt(vb2[0] / c2) + eps);
            }
            ValueModel now = new ValueModel(new Weights(BoardFeatures.NAMES, mean, std, hidden, w1, b1, w2, b2[0], null));
            Fit trainFit = now.fit(train);
            Fit validFit = now.fit(valid);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("epoch", epoch);
            e.put("trainLogLoss", round(trainFit.logLoss()));
            e.put("validLogLoss", round(validFit.logLoss()));
            e.put("validBrier", round(validFit.brier()));
            e.put("validAccuracy", round(validFit.accuracy()));
            epochs.add(e);
            if (log != null) log.accept(e.toString());
            if (validFit.logLoss() < bestLoss - 1e-5) {
                bestLoss = validFit.logLoss();
                sinceBest = 0;
                best = new Weights(BoardFeatures.NAMES, mean.clone(), std.clone(), hidden, deepClone(w1), b1.clone(), w2.clone(), b2[0], null);
            } else if (++sinceBest >= t.patience()) {
                break;
            }
        }
        ValueModel chosen = new ValueModel(best);
        Map<String, Object> training = new LinkedHashMap<>();
        training.put("settings", t);
        training.put("trainSamples", train.size());
        training.put("validSamples", valid.size());
        training.put("train", chosen.fit(train));
        training.put("valid", chosen.fit(valid));
        training.put("epochs", epochs);
        return new ValueModel(new Weights(best.features(), best.mean(), best.std(), best.hidden(), best.w1(), best.b1(), best.w2(), best.b2(), training));
    }

    /** How well it predicts these samples. */
    public Fit fit(List<Sample> samples) {
        double loss = 0, brier = 0, right = 0;
        for (Sample s : samples) {
            double p = Math.min(1 - 1e-7, Math.max(1e-7, predict(s.x())));
            loss -= s.won() * Math.log(p) + (1 - s.won()) * Math.log(1 - p);
            brier += (p - s.won()) * (p - s.won());
            if ((p >= 0.5) == (s.won() >= 0.5)) right++;
        }
        int n = Math.max(1, samples.size());
        return new Fit(loss / n, brier / n, right / n, samples.size());
    }

    // ------------------------------------------------------------------ From its weights (saved and read: ValueModelFiles)

    static ValueModel of(Weights weights) {
        if (!BoardFeatures.NAMES.equals(weights.features())) {
            throw new IllegalArgumentException("The model was trained with other table features: " + weights.features());
        }
        return new ValueModel(weights);
    }

    // ------------------------------------------------------------------ Helpers

    private static double sigmoid(double v) {
        return 1 / (1 + Math.exp(-v));
    }

    private static double[][] deepClone(double[][] a) {
        double[][] out = new double[a.length][];
        for (int i = 0; i < a.length; i++) out[i] = a[i].clone();
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
