package com.drive.drive_manager.simulator.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tunes the weights of the look-ahead bot's table score by winning games (SPSA). Off in the normal build; run it with
 * {@code local-dev/bot-tune.sh}.
 * <p>
 * Each iteration nudges every weight at random up or down at once, giving two opposite versions (θ+ and θ−), and
 * they play each other with every pair of decks from both seats. The weights move toward the version that won, as
 * far as it won ((wins − losses) / games): what is optimized is winning games, nothing else. Several such pairs are
 * played per iteration and averaged, all of their games queued together on one pool of threads. Steps shrink as the
 * iterations go (the usual SPSA gains). While tuning the bots think every candidate move with no time limit, so the
 * result does not depend on how busy the threads are.
 * <p>
 * Every few iterations the current weights play the starting ones (a checkpoint). At the end they play the starting
 * ones again, with the server's time limit, and are accepted only by {@link BotTrainingRun.Criteria}: better with 95 %
 * confidence, no conceded or stuck games, thinking within time. Kept in {@code <out>/<date>_<label>/}: {@code run.json},
 * {@code iterations.jsonl}, {@code checkpoints.jsonl}, {@code games.jsonl} (every game), {@code final.json} and, when
 * accepted, {@code tuned-settings.txt} (to promote to the server's resources by hand).
 */
class BotTuningRun {

    private static final ObjectMapper JSON = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    /** The weights tuned, how big a nudge of each is (its unit) and the range it is kept in. */
    private record Weight(String name, double unit, double min, double max) {}

    /** Every weight that can be tuned; -Dbot.tune.weights picks some by name (default all). */
    private static final List<Weight> ALL = List.of(
            new Weight("life", 2, 1, 40),
            new Weight("hand", 0.5, 0, 6),
            new Weight("guard", 0.15, 0, 2),
            new Weight("exposed", 0.15, 0, 2),
            new Weight("support", 0.2, 0, 3),
            new Weight("structure", 0.2, 0, 3),
            new Weight("lowDeck", 3, 0, 40),
            new Weight("zHand", 0.1, -0.5, 1.5),
            new Weight("zTribute", 0.05, -0.5, 1),
            new Weight("zDiscard", 0.05, -0.5, 1),
            new Weight("gems", 0.2, -2, 3),
            new Weight("aggression", 0.1, 0.3, 2.5),
            new Weight("vStr", 0.15, 0, 3),
            new Weight("vKeyword", 0.15, -1, 3),
            new Weight("vLevel", 0.1, -1, 2),
            new Weight("vAttached", 0.15, -1, 3),
            new Weight("eContinuous", 0.5, -3, 8),
            new Weight("eActivated", 0.5, -3, 8),
            new Weight("eEvent", 0.5, -3, 8),
            new Weight("eSummon", 0.5, -3, 8),
            new Weight("ePlay", 0.5, -3, 8),
            new Weight("eAttack", 0.5, -3, 8),
            new Weight("eDestroy", 0.5, -3, 8),
            new Weight("eInherited", 0.5, -3, 8));

    private static final List<Weight> WEIGHTS = picked(System.getProperty("bot.tune.weights", ""));

    /** A card's own bias in a zone (its prefix): a step of 1 (a card is worth some 5 to 10) and kept between -6 and 10. */
    private static Weight bias(String prefix, String card) {
        return new Weight(prefix + card, 1, -6, 10);
    }

    /**
     * By name; "bias:*", "biasT:*", "biasD:*" stand for that bias of every card of the arena's decks, "bias:<card>" for one.
     * With -Dbot.tune.minExposure (and an exposure.csv from BotExposureRun in bot.tune.out) a "*" leaves out the cards
     * seen in that zone in fewer than that share of the tables: their bias would change no score, only drift.
     */
    private static List<Weight> picked(String names) {
        if (names.isBlank()) return ALL;
        Map<String, Double> exposure = exposure();
        double min = Double.parseDouble(System.getProperty("bot.tune.minExposure", "0"));
        List<Weight> out = new ArrayList<>();
        for (String n : java.util.Arrays.stream(names.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()) {
            String prefix = BotSettings.biasPrefix(n);
            if (prefix != null && n.equals(prefix + "*")) BotMatch.cardNames().stream()
                    .filter(c -> min <= 0 || exposure.getOrDefault(prefix + c, 0.0) >= min)
                    .forEach(c -> out.add(bias(prefix, c)));
            else if (prefix != null) out.add(bias(prefix, n.substring(prefix.length())));
            else out.add(ALL.stream().filter(w -> w.name().equals(n)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown weight: " + n)));
        }
        return out;
    }

    /** While tuning: no time limit (a decision tries all its candidates whatever the threads are doing). */
    private static final String NO_TIME_LIMIT = "budgetMs=1000000";

    @Test
    void tune() throws Exception {
        assumeTrue(Boolean.getBoolean("bot.tune"), "tuning runs only with -Dbot.tune=true");
        int iterations = Integer.getInteger("bot.tune.iterations", 30);
        int pairs = Integer.getInteger("bot.tune.pairs", 4);
        int games = Integer.getInteger("bot.tune.games", 1);
        int checkEvery = Integer.getInteger("bot.tune.checkEvery", 10);
        int finalGames = Integer.getInteger("bot.tune.finalGames", 8);
        int threads = Integer.getInteger("bot.tune.threads", Runtime.getRuntime().availableProcessors());
        double a = Double.parseDouble(System.getProperty("bot.tune.a", "1.5"));
        double c = Double.parseDouble(System.getProperty("bot.tune.c", "1"));
        double bigA = Math.max(1, iterations / 10.0);
        long seed = Long.getLong("bot.tune.seed", System.currentTimeMillis());
        String startText = System.getProperty("bot.tune.start", "");
        BotTrainingRun.Criteria criteria = new BotTrainingRun.Criteria(Double.parseDouble(System.getProperty("bot.tune.minWinLowerBound", "0.5")),
                0, Double.parseDouble(System.getProperty("bot.tune.maxOutOfTimeShare", "0.05")), 0);
        List<String> decks = BotMatch.DECKS;

        String label = System.getProperty("bot.tune.label", "tune").replaceAll("[^A-Za-z0-9._-]", "-");
        Path dir = Path.of(System.getProperty("bot.tune.out", "target/bot-runs"))
                .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "_" + label);
        Files.createDirectories(dir);

        // Continuing another run: its weights (a file in the "k=v,…" form) are the start; the checkpoints and the final
        // match still compare with the default weights, so they show the whole improvement, not this part's only
        String startFile = System.getProperty("bot.tune.startFile", "");
        String fileText = startFile.isBlank() ? "" : Files.readString(Path.of(startFile), StandardCharsets.UTF_8).trim();
        BotSettings start = BotSettings.DEFAULT.with(fileText).with(startText);
        String baselineName = System.getProperty("bot.tune.baseline", startFile.isBlank() ? "start" : "default");
        BotSettings baseline = "default".equals(baselineName) ? BotSettings.DEFAULT : start;
        String continuesFrom = System.getProperty("bot.tune.continuesFrom", "");
        double[] theta = values(start);
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("label", label);
        run.put("startedAt", Instant.now().toString());
        run.put("method", "SPSA on (wins - losses) / games of theta+ against theta-");
        run.put("iterations", iterations);
        run.put("pairsPerIteration", pairs);
        run.put("gamesPerDeckPairAndSeat", games);
        run.put("gamesPerIteration", pairs * decks.size() * decks.size() * 2 * games);
        run.put("threads", threads);
        run.put("processors", Runtime.getRuntime().availableProcessors());
        run.put("gains", Map.of("a", a, "c", c, "A", bigA, "alpha", 0.602, "gamma", 0.101));
        run.put("seed", seed);
        run.put("weights", WEIGHTS);
        run.put("start", start.asMap());
        run.put("startBiases", start.biases());
        run.put("baseline", baselineName);
        if (!continuesFrom.isBlank()) {
            // "<run folder>:<iteration>": where the previous run was paused
            String[] at = continuesFrom.split(":");
            run.put("continuesFrom", Map.of("run", at[0], "iteration", at.length > 1 ? Integer.parseInt(at[1]) : 0));
        }
        if (!startFile.isBlank()) run.put("startFile", startFile);
        run.put("criteria", criteria);
        write(dir.resolve("run.json"), run);
        log("Tuning -> " + dir.toAbsolutePath() + " (" + run.get("gamesPerIteration") + " games per iteration on " + threads + " threads)");

        Random random = new Random(seed);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long t0 = System.nanoTime();
        try {
            for (int k = 0; k < iterations; k++) {
                long iterStart = System.nanoTime();
                double ck = c / Math.pow(k + 1, 0.101);
                double ak = a / Math.pow(k + 1 + bigA, 0.602);
                List<int[]> deltas = new ArrayList<>();
                List<double[][]> versions = new ArrayList<>();
                List<BotDuel.Pending> pending = new ArrayList<>();
                // Every pair's games go to the pool at once
                for (int j = 0; j < pairs; j++) {
                    int[] delta = new int[WEIGHTS.size()];
                    for (int i = 0; i < delta.length; i++) delta[i] = random.nextBoolean() ? 1 : -1;
                    double[] plus = clamp(step(theta, delta, ck));
                    double[] minus = clamp(step(theta, delta, -ck));
                    deltas.add(delta);
                    versions.add(new double[][]{plus, minus});
                    BotSettings sp = settings(start, plus, NO_TIME_LIMIT);
                    BotSettings sm = settings(start, minus, NO_TIME_LIMIT);
                    pending.add(BotDuel.start(() -> new LookaheadBot(sp), () -> new LookaheadBot(sm), decks, games, pool));
                }
                double[] gradient = new double[WEIGHTS.size()];
                List<Map<String, Object>> pairLines = new ArrayList<>();
                for (int j = 0; j < pairs; j++) {
                    BotDuel.Outcome o = pending.get(j).get();
                    double r = o.score();
                    for (int i = 0; i < gradient.length; i++) gradient[i] += r / (2 * ck * deltas.get(j)[i]) / pairs;
                    Map<String, Object> pl = new LinkedHashMap<>();
                    pl.put("delta", deltas.get(j));
                    pl.put("plus", named(versions.get(j)[0]));
                    pl.put("minus", named(versions.get(j)[1]));
                    pl.put("plusScore", BotDuel.round(r));
                    pl.put("match", o.summary());
                    pairLines.add(pl);
                    games(dir, "iteration", k + 1, j, o);
                }
                double[] before = theta.clone();
                for (int i = 0; i < theta.length; i++) theta[i] += ak * gradient[i] * WEIGHTS.get(i).unit();
                theta = clamp(theta);
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("iteration", k + 1);
                line.put("ck", BotDuel.round(ck));
                line.put("ak", BotDuel.round(ak));
                line.put("before", named(before));
                line.put("gradient", named(gradient));
                line.put("after", named(theta));
                line.put("pairs", pairLines);
                line.put("seconds", (System.nanoTime() - iterStart) / 1_000_000_000.0);
                append(dir.resolve("iterations.jsonl"), line);
                log("iteration " + (k + 1) + "/" + iterations + " in " + Math.round((System.nanoTime() - iterStart) / 1e9) + " s -> " + text(theta));

                if (checkEvery > 0 && (k + 1) % checkEvery == 0 && k + 1 < iterations) {
                    BotSettings now = settings(start, theta, NO_TIME_LIMIT);
                    BotSettings base = baseline.with(NO_TIME_LIMIT);
                    BotDuel.Outcome o = BotDuel.start(() -> new LookaheadBot(now), () -> new LookaheadBot(base), decks, 2, pool).get();
                    games(dir, "checkpoint", k + 1, 0, o);
                    Map<String, Object> cp = new LinkedHashMap<>();
                    cp.put("iteration", k + 1);
                    cp.put("weights", named(theta));
                    cp.put("againstStart", o.summary());
                    append(dir.resolve("checkpoints.jsonl"), cp);
                    biases(dir.resolve("card-bias-it" + (k + 1) + ".csv"), theta);
                    log("checkpoint " + (k + 1) + ": against the start " + o.wins() + "-" + o.losses() + " (" + BotDuel.round(o.winRate()) + ")");
                }
            }

            // A card's bias in a zone it is (almost) never in was not learned, only drifted: back to 0 before the final
            // match, so a game where the card does get there counts it at its base worth, not at a random one
            List<String> cleared = clearUnseen(theta, Double.parseDouble(System.getProperty("bot.tune.keepExposure", "0.01")));
            if (!cleared.isEmpty()) log("cleared " + cleared.size() + " biases of cards (almost) never in their zone: " + cleared);
            // The tuned weights against the starting ones, with the server's time limit: accepted only by the criteria
            BotSettings tuned = settings(start, theta, "");
            BotDuel.Outcome o = BotDuel.start(() -> new LookaheadBot(tuned), () -> new LookaheadBot(baseline), decks, finalGames, pool).get();
            games(dir, "final", iterations, 0, o);
            String why = BotTrainingRun.decide(criteria, o.summary());
            Map<String, Object> fin = new LinkedHashMap<>();
            fin.put("tuned", named(theta));
            fin.put("clearedBiases", cleared);
            fin.put("start", named(values(start)));
            fin.put("settings", text(theta));
            fin.put("againstStart", o.summary());
            fin.put("accepted", why == null);
            fin.put("why", why == null ? "better than the starting weights with 95 % confidence" : why);
            fin.put("minutes", BotDuel.round((System.nanoTime() - t0) / 6e10));
            write(dir.resolve("final.json"), fin);
            biases(dir.resolve("card-bias.csv"), theta);
            if (why == null) Files.writeString(dir.resolve("tuned-settings.txt"), text(theta) + System.lineSeparator());
            run.put("finishedAt", Instant.now().toString());
            run.put("final", fin);
            write(dir.resolve("run.json"), run);
            log("final: " + o.wins() + "-" + o.losses() + " (" + BotDuel.round(o.winRate()) + ", 95% from " + BotDuel.round(o.low95()) + ") -> "
                    + (why == null ? "ACCEPTED: " + text(theta) : "kept the starting weights: " + why));
        } finally {
            pool.shutdown();
        }
    }

    // ------------------------------------------------------------------ Weights

    /**
     * Sets to 0 (in place) the biases whose card is in that zone in fewer than {@code keep} of the tables (by
     * exposure.csv; nothing without it). Returns their names.
     */
    private static List<String> clearUnseen(double[] theta, double keep) {
        Map<String, Double> exposure = exposure();
        List<String> cleared = new ArrayList<>();
        if (exposure.isEmpty() || keep <= 0) return cleared;
        for (int i = 0; i < theta.length; i++) {
            String name = WEIGHTS.get(i).name();
            if (BotSettings.biasPrefix(name) == null || exposure.getOrDefault(name, 0.0) >= keep) continue;
            theta[i] = 0;
            cleared.add(name);
        }
        return cleared;
    }

    /** Share of the tables where each card is in each zone, by bias key ("biasD:cueva de bestias"); empty without the file. */
    private static Map<String, Double> exposure() {
        Path file = Path.of(System.getProperty("bot.tune.out", "target/bot-runs")).resolve("exposure.csv");
        Map<String, Double> m = new LinkedHashMap<>();
        if (!Files.exists(file)) return m;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines.subList(1, lines.size())) {
                String[] f = line.split(",");
                if (f.length < 5) continue;
                m.put(BotSettings.BIAS + f[0], Double.parseDouble(f[1]));
                m.put(BotSettings.BIAS_TRIBUTE + f[0], Double.parseDouble(f[3]));
                m.put(BotSettings.BIAS_DISCARD + f[0], Double.parseDouble(f[4]));
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return m;
    }

    private static double[] values(BotSettings s) {
        return WEIGHTS.stream().mapToDouble(w -> s.get(w.name())).toArray();
    }

    private static double[] step(double[] theta, int[] delta, double size) {
        double[] out = theta.clone();
        for (int i = 0; i < out.length; i++) out[i] += size * delta[i] * WEIGHTS.get(i).unit();
        return out;
    }

    private static double[] clamp(double[] theta) {
        double[] out = theta.clone();
        for (int i = 0; i < out.length; i++) out[i] = Math.max(WEIGHTS.get(i).min(), Math.min(WEIGHTS.get(i).max(), out[i]));
        return out;
    }

    /** "life=10.42,hand=1.5,…": the weights as {@link BotSettings#with} reads them. */
    private static String text(double[] theta) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < theta.length; i++) parts.add(WEIGHTS.get(i).name() + "=" + String.format(Locale.ROOT, "%.4f", theta[i]));
        return String.join(",", parts);
    }

    private static BotSettings settings(BotSettings start, double[] theta, String extra) {
        return start.with(text(theta) + (extra.isEmpty() ? "" : "," + extra));
    }

    private static Map<String, Double> named(double[] v) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < v.length; i++) m.put(WEIGHTS.get(i).name(), BotDuel.round(v[i]));
        return m;
    }

    // ------------------------------------------------------------------ Files

    private static synchronized void games(Path dir, String phase, int iteration, int pair, BotDuel.Outcome o) throws IOException {
        String lines = o.games().stream().map(g -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("phase", phase);
            m.put("iteration", iteration);
            m.put("pair", pair);
            m.putAll(g);
            try {
                return JSON.writeValueAsString(m);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }).collect(Collectors.joining(System.lineSeparator(), "", System.lineSeparator()));
        Files.writeString(dir.resolve("games.jsonl"), lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** The cards' biases tuned so far, most valued first (when the run tunes any). */
    private static void biases(Path file, double[] theta) throws IOException {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < theta.length; i++) {
            String name = WEIGHTS.get(i).name();
            String prefix = BotSettings.biasPrefix(name);
            if (prefix != null) rows.add(prefix.substring(0, prefix.length() - 1) + "," + name.substring(prefix.length()) + "," + String.format(Locale.ROOT, "%.4f", theta[i]));
        }
        if (rows.isEmpty()) return;
        rows.sort(java.util.Comparator.comparingDouble((String r) -> -Double.parseDouble(r.substring(r.lastIndexOf(',') + 1))));
        Files.writeString(file, "zone,card,bias" + System.lineSeparator() + String.join(System.lineSeparator(), rows) + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static void write(Path file, Object value) throws IOException {
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardCharsets.UTF_8);
    }

    private static void append(Path file, Object value) throws IOException {
        Files.writeString(file, JSON.writeValueAsString(value) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void log(String line) {
        System.out.println("[tune " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + line);
    }
}
