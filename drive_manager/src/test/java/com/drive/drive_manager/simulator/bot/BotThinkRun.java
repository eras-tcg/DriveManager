package com.drive.drive_manager.simulator.bot;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Does the look-ahead bot get stronger when it thinks more? Each variant ("samples=8,maxCandidates=32", …) plays the
 * base (the default bot, or {@code bot.think.base}) in paired games (BotRatchetRun#paired: each deal twice, seats
 * swapped), with no time limit, so only how much it thinks differs. The variants play at the same time, in rounds of
 * one seed (32 games each, the same deals for every variant), and each stops as soon as a three-way sequential test
 * on the deal scores decides: stronger (wins about 50 % + delta), weaker (50 % - delta) or even (neither), or after
 * {@code maxGames}. A decided variant leaves the threads to the rest. It also times each decision of both bots, to
 * know what thinking that much costs. Run with {@code -Dbot.think=true -Dtest=BotThinkRun}; kept in
 * {@code <out>/<date>_think/}: run.json, games.jsonl, status.json (each variant after every round).
 */
class BotThinkRun {

    @Test
    void think() throws Exception {
        assumeTrue(Boolean.getBoolean("bot.think"), "runs only with -Dbot.think=true");
        int threads = Integer.getInteger("bot.think.threads", Runtime.getRuntime().availableProcessors());
        int minGames = Integer.getInteger("bot.think.minGames", 64);
        int maxGames = Integer.getInteger("bot.think.maxGames", 512);
        double delta = Double.parseDouble(System.getProperty("bot.think.delta", "0.06"));
        double alpha = Double.parseDouble(System.getProperty("bot.think.alpha", "0.05"));
        double floor = Double.parseDouble(System.getProperty("bot.think.varianceFloor", "0.02"));
        String baseText = System.getProperty("bot.think.base", "").trim();
        // Another offset, other deals: to check a result on games it has not seen
        int seedOffset = Integer.getInteger("bot.think.seedOffset", 0);
        // 16: each round plays half the pairs of decks (16 games), alternating halves; 32: all of them
        int parts = Math.max(1, Math.min(8, 32 / Math.max(1, Integer.getInteger("bot.think.roundGames", 32))));
        List<String> variants = Stream.of(System.getProperty("bot.think.variants", "samples=4,maxCandidates=24").split(";"))
                .map(String::trim).filter(v -> !v.isEmpty()).toList();
        // bot.think.resume: a run's folder (same base and variants) whose games count and that goes on, in that folder,
        // on new deals (give it another seedOffset)
        String resume = System.getProperty("bot.think.resume", "").trim();
        Path dir = resume.isEmpty() ? Path.of(System.getProperty("bot.think.out", "target/bot-runs"))
                .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "_think")
                : Path.of(System.getProperty("bot.think.out", "target/bot-runs")).resolve(resume);
        Files.createDirectories(dir);
        BotSettings base = BotSettings.DEFAULT.with("budgetMs=1000000");
        if (!baseText.isEmpty()) base = base.with(baseText);
        // Both one-sided tests use the same error rates, so one bound: accept at +bound, reject at -bound
        double bound = Math.log((1 - alpha) / alpha);
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("method", "think: each variant vs the base in paired games until a three-way pair SPRT decides (stronger, weaker, or even within delta)");
        run.put("base", baseText.isEmpty() ? "default" : baseText);
        run.put("variants", variants);
        run.put("sprt", Map.of("delta", delta, "alpha", alpha, "bound", BotDuel.round(bound), "minGames", minGames, "maxGames", maxGames, "varianceFloor", floor));
        run.put("seedOffset", seedOffset);
        run.put("roundGames", 32 / parts);
        run.put("threads", threads);
        run.put("startedAt", LocalDateTime.now().toString());
        if (!resume.isEmpty() && Files.exists(dir.resolve("run.json"))) {
            // The run as it began, with when it went on
            Map<String, Object> was = BotRatchetRun.JSON.readValue(dir.resolve("run.json").toFile(), Map.class);
            List<Object> resumed = new ArrayList<>(was.get("resumed") instanceof List<?> l ? l : List.of());
            resumed.add(Map.of("at", LocalDateTime.now().toString(), "seedOffset", seedOffset, "roundGames", 32 / parts));
            was.put("resumed", resumed);
            // The test as it goes on now (its confidence may have changed)
            was.put("sprt", run.get("sprt"));
            was.put("roundGames", run.get("roundGames"));
            run = was;
        }
        Files.writeString(dir.resolve("run.json"), BotRatchetRun.JSON.writeValueAsString(run), StandardCharsets.UTF_8);

        List<Tally> tallies = variants.stream().map(Tally::new).toList();
        // The games already played, round by round (a round: the games of one iteration)
        Map<String, Integer> lastRound = new HashMap<>();
        if (!resume.isEmpty() && Files.exists(dir.resolve("games.jsonl"))) {
            Map<String, TreeMap<Integer, List<Map<String, Object>>>> old = new HashMap<>();
            for (String line : Files.readAllLines(dir.resolve("games.jsonl"), StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                Map<String, Object> g = BotRatchetRun.JSON.readValue(line, Map.class);
                old.computeIfAbsent(String.valueOf(g.get("phase")), k -> new TreeMap<>())
                        .computeIfAbsent(((Number) g.get("iteration")).intValue(), k -> new ArrayList<>()).add(g);
            }
            for (Tally t : tallies) {
                TreeMap<Integer, List<Map<String, Object>>> rounds = old.getOrDefault("think " + t.variant, new TreeMap<>());
                rounds.values().forEach(t::add);
                if (!rounds.isEmpty()) lastRound.put(t.variant, rounds.lastKey());
                System.out.println("[think] " + t.variant + ": " + t.games + " games kept from " + resume);
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        // Each variant's rounds run on their own thread, so all variants keep the pool busy at once
        ExecutorService rounds = Executors.newFixedThreadPool(tallies.size());
        long start = System.nanoTime();
        BotSettings against = base;
        try {
            List<Future<?>> running = new ArrayList<>();
            for (Tally t : tallies) {
                BotSettings more = base.with(t.variant);
                int first = lastRound.getOrDefault(t.variant, 0);
                running.add(rounds.submit(() -> {
                    for (int s = first + 1; t.decision == null; s++) {
                        int k = s - first;
                        // control.json in the run's folder, read before each round: {"pause": [variants], "stop": [variants]}.
                        // A paused variant waits (its threads go to the others); a stopped one ends with no verdict
                        for (Map<String, Object> c = control(dir); ; c = control(dir)) {
                            boolean stop = listed(c, "stop", t.variant), pause = listed(c, "pause", t.variant);
                            if (stop) t.decision = "stopped";
                            if (t.paused != (pause && !stop)) {
                                t.paused = pause && !stop;
                                synchronized (tallies) { status(dir, tallies, (System.nanoTime() - start) / 1e9, false); }
                            }
                            if (stop || !pause) break;
                            Thread.sleep(5000);
                        }
                        if (t.decision != null) {
                            synchronized (tallies) { status(dir, tallies, (System.nanoTime() - start) / 1e9, false); }
                            break;
                        }
                        // The same deals for every variant in a round, so they are compared on equal luck
                        // A round is half the pairs of decks (16 games) when bot.think.roundGames is 16; two rounds cover them all
                        List<Map<String, Object>> games = BotRatchetRun.paired(more, against, "lookahead", BotMatch.DECKS, 1, parts, (k - 1) % parts,
                                new Random(1000L * ((k - 1) / parts + 1 + seedOffset)), pool, "think " + t.variant, s, dir);
                        synchronized (tallies) {
                            t.add(games);
                            t.up = t.pairs.llr(0.5 + delta, floor);
                            t.down = t.pairs.llr(0.5 - delta, floor);
                            if (t.games >= minGames) {
                                if (t.up >= bound) t.decision = "stronger";
                                else if (t.down >= bound) t.decision = "weaker";
                                else if (t.up <= -bound && t.down <= -bound) t.decision = "even";
                                else if (t.games >= maxGames) t.decision = "undecided";
                            }
                            double seconds = (System.nanoTime() - start) / 1e9;
                            System.out.println(t.line(s, seconds));
                            status(dir, tallies, seconds, false);
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : running) f.get();
            synchronized (tallies) {
                status(dir, tallies, (System.nanoTime() - start) / 1e9, true);
            }
        } finally {
            rounds.shutdown();
            pool.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> control(Path dir) {
        try {
            Path f = dir.resolve("control.json");
            return Files.exists(f) ? BotRatchetRun.JSON.readValue(f.toFile(), Map.class) : Map.of();
        } catch (Exception e) {
            // Half written or wrong: as if there were none, until it is right
            return Map.of();
        }
    }

    private static boolean listed(Map<String, Object> control, String key, String variant) {
        return control.get(key) instanceof List<?> l && l.stream().anyMatch(v -> variant.equals(String.valueOf(v)));
    }

    private static void status(Path dir, List<Tally> tallies, double seconds, boolean finished) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("updatedAt", LocalDateTime.now().toString());
        m.put("seconds", Math.round(seconds));
        m.put("finished", finished);
        m.put("variants", tallies.stream().map(Tally::summary).toList());
        Files.writeString(dir.resolve("status.json"), BotRatchetRun.JSON.writeValueAsString(m), StandardCharsets.UTF_8);
    }

    /** One variant's games against the base so far, what they cost, and what the test says. */
    static final class Tally {
        final String variant;
        final BotRatchetRun.Pairs pairs = new BotRatchetRun.Pairs();
        long games, wins, losses, decisions, baseDecisions, outOfTime;
        // How each side used its chances: special summons possible and made, cards sent from the hand to the Tributos
        final long[] specialChances = new long[2], specials = new long[2], tributedCreatures = new long[2], tributedOthers = new long[2];
        double ms, thinkMs, maxThinkMs, baseThinkMs, up, down;
        volatile String decision;
        volatile boolean paused;

        Tally(String variant) {
            this.variant = variant;
        }

        void add(List<Map<String, Object>> round) {
            pairs.add(round);
            for (Map<String, Object> g : round) {
                games++;
                ms += ((Number) g.get("ms")).doubleValue();
                if ("a".equals(g.get("winner"))) wins++;
                else if ("b".equals(g.get("winner"))) losses++;
                if (g.get("aThink") instanceof Map<?, ?> a && g.get("bThink") instanceof Map<?, ?> b) {
                    decisions += ((Number) a.get("decisions")).longValue();
                    thinkMs += ((Number) a.get("ms")).doubleValue();
                    maxThinkMs = Math.max(maxThinkMs, ((Number) a.get("maxMs")).doubleValue());
                    outOfTime += ((Number) a.get("outOfTime")).longValue();
                    baseDecisions += ((Number) b.get("decisions")).longValue();
                    baseThinkMs += ((Number) b.get("ms")).doubleValue();
                    int side = 0;
                    for (Map<?, ?> t : List.of(a, b)) {
                        specialChances[side] += count(t, "specialChances");
                        specials[side] += count(t, "specials");
                        tributedCreatures[side] += count(t, "tributedCreatures");
                        tributedOthers[side] += count(t, "tributedOthers");
                        side++;
                    }
                }
            }
        }

        private static long count(Map<?, ?> m, String key) {
            return m.get(key) instanceof Number n ? n.longValue() : 0;
        }

        /** Special summons made when one was among the moves, and creatures among the cards sent to the Tributos (side 0: the variant). */
        double specialRate(int side) {
            return specialChances[side] == 0 ? 0 : (double) specials[side] / specialChances[side];
        }

        double creatureTributes(int side) {
            long all = tributedCreatures[side] + tributedOthers[side];
            return all == 0 ? 0 : (double) tributedCreatures[side] / all;
        }

        double rate() {
            return wins + losses == 0 ? 0.5 : (double) wins / (wins + losses);
        }

        Map<String, Object> summary() {
            double[] ci = BotDuel.wilson(wins, wins + losses);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("variant", variant);
            m.put("paused", paused);
            m.put("games", games);
            m.put("wins", wins);
            m.put("losses", losses);
            m.put("winRate", BotDuel.round(rate()));
            m.put("winLow95", BotDuel.round(ci[0]));
            m.put("winHigh95", BotDuel.round(ci[1]));
            m.put("pairs", pairs.n());
            m.put("pairMean", BotDuel.round(pairs.mean()));
            m.put("llrStronger", BotDuel.round(up));
            m.put("llrWeaker", BotDuel.round(down));
            m.put("decision", decision);
            m.put("secondsPerGame", BotDuel.round(games == 0 ? 0 : ms / games / 1000));
            m.put("msPerDecision", BotDuel.round(decisions == 0 ? 0 : thinkMs / decisions));
            m.put("maxMsPerDecision", BotDuel.round(maxThinkMs));
            m.put("baseMsPerDecision", BotDuel.round(baseDecisions == 0 ? 0 : baseThinkMs / baseDecisions));
            m.put("specialRate", BotDuel.round(specialRate(0)));
            m.put("baseSpecialRate", BotDuel.round(specialRate(1)));
            m.put("specialChances", specialChances[0]);
            m.put("creatureTributes", BotDuel.round(creatureTributes(0)));
            m.put("baseCreatureTributes", BotDuel.round(creatureTributes(1)));
            return m;
        }

        String line(int round, double seconds) {
            double[] ci = BotDuel.wilson(wins, wins + losses);
            return String.format(Locale.ROOT, "[think] %s vs base | round %d: %d-%d (%.3f, 95%% %.3f-%.3f) | %d games | LLR stronger %+.2f weaker %+.2f | %.0f ms/decision (base %.0f, max %.0f) | special summons %.0f%% (base %.0f%%) | creatures tributed %.0f%% (base %.0f%%) | %.1f s/game | %s | %.0f s",
                    variant, round, wins, losses, rate(), ci[0], ci[1], games, up, down,
                    decisions == 0 ? 0 : thinkMs / decisions, baseDecisions == 0 ? 0 : baseThinkMs / baseDecisions, maxThinkMs,
                    specialRate(0) * 100, specialRate(1) * 100, creatureTributes(0) * 100, creatureTributes(1) * 100,
                    ms / games / 1000, decision == null ? "playing" : decision.toUpperCase(Locale.ROOT), seconds);
        }
    }
}
