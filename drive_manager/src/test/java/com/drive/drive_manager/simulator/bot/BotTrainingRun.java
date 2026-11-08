package com.drive.drive_manager.simulator.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Trains the bot's value model by itself, generation after generation (self-play). Off in the normal build; run it
 * with {@code local-dev/bot-train.sh}.
 * <ol>
 *   <li>The champion (at first the look-ahead bot with hand-set weights) plays itself; every table seen, with who
 *   won, is a sample.</li>
 *   <li>A model is trained on all the samples so far ({@link ValueModel#train}: it minimizes the log loss on the
 *   validation samples, with early stopping).</li>
 *   <li>The candidate (the look-ahead bot scoring with that model) plays the champion with every pair of decks,
 *   from both seats. It is promoted only by the {@link Criteria}: better with 95 % confidence, no conceded games
 *   or errors, and thinking within its time.</li>
 *   <li>The champion plays itself again for more samples, and so on, until the generations run out or no
 *   candidate is promoted for {@code patience} generations in a row.</li>
 * </ol>
 * Everything is kept in {@code <out>/<date>_<label>/}: {@code run.json} (settings and, at the end, the outcome),
 * {@code generations.jsonl} (one line per generation: training fit, match and decision), {@code data/gen-N.csv}
 * (the samples), {@code models/gen-N.json} (every model), {@code games/gen-N.jsonl} (every evaluation game) and
 * {@code champion.json} (the best model, to promote to the server's resources by hand).
 */
class BotTrainingRun {

    private static final ObjectMapper JSON = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    /**
     * When a candidate replaces the champion, and when training stops.
     *
     * @param minWinLowerBound    the lower end of the candidate's 95 % win interval (Wilson) must be above this
     * @param maxConceded         games the candidate may concede for want of a valid move
     * @param maxOutOfTimeShare   share of its decisions that may run out of time
     * @param patience            generations in a row without a promotion before stopping
     */
    record Criteria(double minWinLowerBound, int maxConceded, double maxOutOfTimeShare, int patience) {}

    /** The run's settings (system properties "bot.train.*"). */
    record Plan(int generations, int selfPlayGames, int evalGamesPerDeckPair, int threads, List<String> decks,
                Criteria criteria, ValueModel.Training training) {}

    @Test
    void train() throws Exception {
        assumeTrue(Boolean.getBoolean("bot.train"), "training runs only with -Dbot.train=true");
        Plan plan = new Plan(
                Integer.getInteger("bot.train.generations", 4),
                Integer.getInteger("bot.train.selfPlay", 192),
                Integer.getInteger("bot.train.evalGames", 4),
                Integer.getInteger("bot.train.threads", Runtime.getRuntime().availableProcessors()),
                BotMatch.DECKS,
                new Criteria(Double.parseDouble(System.getProperty("bot.train.minWinLowerBound", "0.5")),
                        Integer.getInteger("bot.train.maxConceded", 0),
                        Double.parseDouble(System.getProperty("bot.train.maxOutOfTimeShare", "0.05")),
                        Integer.getInteger("bot.train.patience", 2)),
                new ValueModel.Training(Integer.getInteger("bot.train.hidden", 16), Integer.getInteger("bot.train.epochs", 60),
                        256, Double.parseDouble(System.getProperty("bot.train.learningRate", "0.003")),
                        Double.parseDouble(System.getProperty("bot.train.l2", "1e-4")), 0.15, 8, 1));
        String label = System.getProperty("bot.train.label", "train").replaceAll("[^A-Za-z0-9._-]", "-");
        Path dir = Path.of(System.getProperty("bot.train.out", "target/bot-runs"))
                .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "_" + label);
        Files.createDirectories(dir.resolve("data"));
        Files.createDirectories(dir.resolve("models"));
        Files.createDirectories(dir.resolve("games"));
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("label", label);
        run.put("startedAt", Instant.now().toString());
        run.put("plan", plan);
        run.put("features", BoardFeatures.NAMES);
        run.put("lookahead", BotSettings.DEFAULT.asMap());
        write(dir.resolve("run.json"), run);
        log("Training -> " + dir.toAbsolutePath());

        List<ValueModel.Sample> data = new ArrayList<>();
        ValueModel champion = null;
        int championGen = 0;
        data.addAll(selfPlay(plan, dir, 0, null));
        int sincePromotion = 0;
        List<Map<String, Object>> history = new ArrayList<>();
        for (int gen = 1; gen <= plan.generations(); gen++) {
            final int g = gen;
            ValueModel candidate = ValueModel.train(data, plan.training(), line -> {});
            ValueModelFiles.save(candidate, dir.resolve("models/gen-" + gen + ".json"));
            log("gen " + gen + ": trained on " + data.size() + " samples, " + candidate.weights().training().get("valid"));

            ValueModel champ = champion;
            Map<String, Object> match = match(plan, dir.resolve("games/gen-" + gen + ".jsonl"),
                    () -> new LookaheadBot(BotSettings.DEFAULT, candidate),
                    () -> new LookaheadBot(BotSettings.DEFAULT, champ));
            String why = decide(plan.criteria(), match);
            boolean promoted = why == null;
            if (promoted) {
                champion = candidate;
                championGen = gen;
                ValueModelFiles.save(candidate, dir.resolve("champion.json"));
                sincePromotion = 0;
            } else {
                sincePromotion++;
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("generation", g);
            line.put("samples", data.size());
            line.put("fit", candidate.weights().training().get("valid"));
            line.put("epochs", ((List<?>) candidate.weights().training().get("epochs")).size());
            line.put("match", match);
            line.put("promoted", promoted);
            line.put("why", promoted ? "better than the champion with 95 % confidence" : why);
            line.put("champion", championGen == 0 ? "hand-set weights" : "gen-" + championGen);
            line.put("finishedAt", Instant.now().toString());
            history.add(line);
            append(dir.resolve("generations.jsonl"), line);
            log("gen " + gen + ": " + match.get("wins") + "-" + match.get("losses") + " (" + match.get("winRate") + ", 95% from "
                    + match.get("winLow95") + ") -> " + (promoted ? "PROMOTED" : "kept the champion: " + why));
            if (sincePromotion >= plan.criteria().patience()) {
                log("No promotion for " + sincePromotion + " generations: stopping");
                break;
            }
            if (gen < plan.generations()) data.addAll(selfPlay(plan, dir, gen, champion));
        }
        run.put("finishedAt", Instant.now().toString());
        run.put("champion", championGen == 0 ? "hand-set weights (no model was promoted)" : "gen-" + championGen);
        run.put("generations", history);
        write(dir.resolve("run.json"), run);
        log("Champion: " + run.get("champion"));
    }

    // ------------------------------------------------------------------ Games

    /** The champion plays itself; the tables seen are kept (data/gen-N.csv) and returned as samples. */
    private List<ValueModel.Sample> selfPlay(Plan plan, Path dir, int gen, ValueModel champion) throws Exception {
        long start = System.nanoTime();
        List<ValueModel.Sample> samples = new ArrayList<>();
        List<String> decks = plan.decks();
        List<Supplier<BotMatch.Result>> games = new ArrayList<>();
        for (int i = 0; i < plan.selfPlayGames(); i++) {
            String a = decks.get(i % decks.size());
            String b = decks.get((i / decks.size()) % decks.size());
            games.add(() -> BotMatch.play(a, b, new LookaheadBot(BotSettings.DEFAULT, champion),
                    new LookaheadBot(BotSettings.DEFAULT, champion), true));
        }
        List<BotMatch.Result> results = parallel(plan.threads(), games);
        for (int i = 0; i < results.size(); i++) samples.addAll(results.get(i).samples(gen * 1_000_000L + i));
        try (BufferedWriter out = Files.newBufferedWriter(dir.resolve("data/gen-" + gen + ".csv"), StandardCharsets.UTF_8)) {
            out.write("game," + String.join(",", BoardFeatures.NAMES) + ",won");
            out.newLine();
            for (ValueModel.Sample s : samples) {
                StringBuilder sb = new StringBuilder().append(s.game()).append(',');
                for (double v : s.x()) sb.append(v).append(',');
                out.write(sb.append((int) s.won()).toString());
                out.newLine();
            }
        }
        log("gen " + gen + ": self-play " + plan.selfPlayGames() + " games -> " + samples.size() + " samples in "
                + (System.nanoTime() - start) / 1_000_000_000 + " s");
        return samples;
    }

    /** The candidate against the champion with every pair of decks, from both seats; every game is kept. */
    private Map<String, Object> match(Plan plan, Path gamesFile, Supplier<LookaheadBot> candidate, Supplier<LookaheadBot> champion) throws Exception {
        record Seat(String aDeck, String bDeck, boolean candidateHost) {}
        List<Seat> seats = new ArrayList<>();
        for (String a : plan.decks()) for (String b : plan.decks()) for (int g = 0; g < plan.evalGamesPerDeckPair(); g++) {
            seats.add(new Seat(a, b, true));
            seats.add(new Seat(a, b, false));
        }
        List<Supplier<Map<String, Object>>> games = new ArrayList<>();
        for (Seat s : seats) {
            games.add(() -> {
                LookaheadBot c = candidate.get();
                LookaheadBot k = champion.get();
                BotMatch.Result r = s.candidateHost() ? BotMatch.play(s.aDeck(), s.bDeck(), c, k) : BotMatch.play(s.bDeck(), s.aDeck(), k, c);
                String cSeat = s.candidateHost() ? "host" : "guest";
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("candidateDeck", s.aDeck());
                m.put("championDeck", s.bDeck());
                m.put("candidateSeat", cSeat);
                m.put("winner", r.winner() == null ? null : r.winner().equals(cSeat) ? "candidate" : "champion");
                m.put("reason", r.reason());
                m.put("turns", r.turns());
                m.put("candidateConceded", cSeat.equals(r.conceded()));
                m.put("stuckAt", r.stuckAt());
                m.put("ms", r.millis());
                LookaheadBot.Stats st = c.stats();
                m.put("candidateDecisions", st.decisions());
                m.put("candidateOutOfTime", st.outOfTime());
                m.put("candidateAvgMs", st.decisions() == 0 ? 0 : st.totalNanos() / 1e6 / st.decisions());
                return m;
            });
        }
        List<Map<String, Object>> results = parallel(plan.threads(), games);
        try (BufferedWriter out = Files.newBufferedWriter(gamesFile, StandardCharsets.UTF_8)) {
            for (Map<String, Object> m : results) {
                out.write(JSON.writeValueAsString(m));
                out.newLine();
            }
        }
        long wins = results.stream().filter(m -> "candidate".equals(m.get("winner"))).count();
        long losses = results.stream().filter(m -> "champion".equals(m.get("winner"))).count();
        long decisions = results.stream().mapToLong(m -> ((Number) m.get("candidateDecisions")).longValue()).sum();
        long outOfTime = results.stream().mapToLong(m -> ((Number) m.get("candidateOutOfTime")).longValue()).sum();
        double[] ci = wilson(wins, wins + losses);
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("games", results.size());
        match.put("wins", wins);
        match.put("losses", losses);
        match.put("unfinished", results.size() - wins - losses);
        match.put("winRate", round(wins + losses == 0 ? 0 : (double) wins / (wins + losses)));
        match.put("winLow95", round(ci[0]));
        match.put("winHigh95", round(ci[1]));
        match.put("conceded", results.stream().filter(m -> Boolean.TRUE.equals(m.get("candidateConceded"))).count());
        match.put("stuck", results.stream().filter(m -> m.get("stuckAt") != null).count());
        match.put("outOfTimeShare", round(decisions == 0 ? 0 : (double) outOfTime / decisions));
        match.put("avgDecisionMs", round(results.stream().mapToDouble(m -> ((Number) m.get("candidateAvgMs")).doubleValue()).average().orElse(0)));
        return match;
    }

    /** Why the candidate is not promoted, or null when it is (see {@link Criteria}). */
    static String decide(Criteria c, Map<String, Object> match) {
        double low = ((Number) match.get("winLow95")).doubleValue();
        long conceded = ((Number) match.get("conceded")).longValue();
        long stuck = ((Number) match.get("stuck")).longValue();
        double outOfTime = ((Number) match.get("outOfTimeShare")).doubleValue();
        if (conceded > c.maxConceded()) return "it conceded " + conceded + " games";
        if (stuck > 0) return stuck + " games got stuck";
        if (outOfTime > c.maxOutOfTimeShare()) return "it ran out of time in " + outOfTime + " of its decisions";
        if (low <= c.minWinLowerBound()) return "not better with 95 % confidence (its interval starts at " + low + ")";
        return null;
    }

    private static <T> List<T> parallel(int threads, List<Supplier<T>> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Supplier<T> w : work) futures.add(pool.submit(w::get));
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) out.add(f.get());
            return out;
        } finally {
            pool.shutdown();
        }
    }

    // ------------------------------------------------------------------ Helpers

    private static double[] wilson(long wins, long n) {
        if (n == 0) return new double[]{0, 0};
        double z = 1.96;
        double p = (double) wins / n;
        double center = (p + z * z / (2 * n)) / (1 + z * z / n);
        double half = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / (1 + z * z / n);
        return new double[]{Math.max(0, center - half), Math.min(1, center + half)};
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private static void write(Path file, Object value) throws IOException {
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardCharsets.UTF_8);
    }

    private static void append(Path file, Object value) throws IOException {
        Files.writeString(file, JSON.writeValueAsString(value) + System.lineSeparator(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    private static void log(String line) {
        System.out.println("[train " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + line);
    }
}
