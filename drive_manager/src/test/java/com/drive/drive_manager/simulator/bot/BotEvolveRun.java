package com.drive.drive_manager.simulator.bot;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Evolves some of the bot's parameters by playing (separable natural evolution strategy, sNES). Each generation draws
 * {@code bot.evolve.pairs} pairs of children around the centre, mirror images of each other (centre + sigma * z and
 * centre - sigma * z, so most of the luck of the draw cancels), and every child plays the same deals: each pair of
 * starter decks, both seats, against a rival from a pool (the bot it started from, the original hard bot, and the best
 * child of each of the last generations: it must beat them all, not just exploit one). Its fitness is its share of
 * those games. Every game moves the centre a little toward the children that won more (ranked, so a lucky streak does
 * not dominate) and adapts each parameter's sigma: wider while moving it pays, narrower near its best. Every
 * {@code bot.evolve.checkEvery} generations the centre plays the original hard bot on fresh deals, so the growth is
 * checked, not assumed. Only the parameters named in {@code bot.evolve.params} ("name:start:sigma,...") move; every
 * other parameter stays as in {@code bot.evolve.base}, and run.json lists them all. Run with
 * {@code -Dbot.evolve=true -Dtest=BotEvolveRun}; kept in {@code <out>/<date>_evolve/}: run.json, generations.jsonl,
 * checks.jsonl, games.jsonl.
 */
class BotEvolveRun {

    private static final String HARD = "turns=2,samples=2,maxCandidates=24,sameDeals=1,halving=1,hMullCost=1,hMullKeep=0,hEvenTrade=1.1445";

    @Test
    void evolve() throws Exception {
        assumeTrue(Boolean.getBoolean("bot.evolve"), "runs only with -Dbot.evolve=true");
        int generations = Integer.getInteger("bot.evolve.generations", 40);
        int pairs = Integer.getInteger("bot.evolve.pairs", 6);
        int checkEvery = Integer.getInteger("bot.evolve.checkEvery", 5);
        int hallOfFame = Integer.getInteger("bot.evolve.hallOfFame", 3);
        int threads = Integer.getInteger("bot.evolve.threads", Runtime.getRuntime().availableProcessors());
        long seed = Long.getLong("bot.evolve.seed", 20261104L);
        String baseText = System.getProperty("bot.evolve.base", HARD + ",sHand=1,sField=4");
        String spec = System.getProperty("bot.evolve.params", "sHand:1:1,sField:4:2,uTribute:0:1.5,vAttached:0.5:1,vTop:0:2,vStrSq:0:0.02");
        Path dir = Path.of(System.getProperty("bot.evolve.out", "target/bot-runs"))
                .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "_evolve");
        Files.createDirectories(dir);

        List<String> names = new ArrayList<>();
        List<Double> start = new ArrayList<>(), startSigma = new ArrayList<>();
        for (String p : spec.split(",")) {
            String[] f = p.trim().split(":");
            names.add(f[0]);
            start.add(Double.parseDouble(f[1]));
            startSigma.add(Double.parseDouble(f[2]));
        }
        int d = names.size();
        double[] mean = start.stream().mapToDouble(Double::doubleValue).toArray();
        double[] sigma = startSigma.stream().mapToDouble(Double::doubleValue).toArray();
        BotSettings base = BotSettings.DEFAULT.with(baseText);
        // sNES learning rates: the centre moves by the full estimated step; sigma more slowly
        double etaMean = 1.0, etaSigma = (3 + Math.log(d)) / (5 * Math.sqrt(d));

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("method", "evolve: separable natural evolution strategy (sNES), mirrored children, rival pool, fitness = share of paired games");
        run.put("base", baseText);
        List<Map<String, Object>> evolving = new ArrayList<>();
        for (int i = 0; i < d; i++) evolving.add(Map.of("name", names.get(i), "start", mean[i], "sigma", sigma[i], "min", 0.0));
        run.put("evolving", evolving);
        // Every parameter of the model: the evolving ones above, the rest frozen at the base's value
        Map<String, Object> frozen = new LinkedHashMap<>();
        base.values().forEach((k, v) -> { if (!names.contains(k)) frozen.put(k, v); });
        run.put("frozen", frozen);
        run.put("generations", generations);
        run.put("children", 2 * pairs);
        run.put("gamesPerChild", 2 * BotMatch.DECKS.size() * BotMatch.DECKS.size());
        run.put("checkEvery", checkEvery);
        run.put("threads", threads);
        run.put("startedAt", LocalDateTime.now().toString());
        Files.writeString(dir.resolve("run.json"), BotRatchetRun.JSON.writeValueAsString(run), StandardCharsets.UTF_8);

        // The rivals every child plays: the bot it started from, the original hard bot, then the best children
        List<Map.Entry<String, BotSettings>> rivals = new ArrayList<>();
        rivals.add(Map.entry("inicio", base));
        rivals.add(Map.entry("Difícil", BotSettings.DEFAULT.with(HARD)));
        Random rnd = new Random(seed);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long t0 = System.nanoTime();
        try {
            for (int g = 1; g <= generations; g++) {
                double[][] z = new double[2 * pairs][d];
                for (int k = 0; k < pairs; k++) {
                    for (int i = 0; i < d; i++) {
                        double n = rnd.nextGaussian();
                        z[2 * k][i] = n;
                        z[2 * k + 1][i] = -n;
                    }
                }
                BotSettings[] children = new BotSettings[2 * pairs];
                String[] texts = new String[2 * pairs];
                for (int c = 0; c < children.length; c++) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < d; i++) {
                        double v = Math.max(0, mean[i] + sigma[i] * z[c][i]);
                        sb.append(i == 0 ? "" : ",").append(names.get(i)).append('=').append(String.format(Locale.ROOT, "%.4f", v));
                    }
                    texts[c] = sb.toString();
                    children[c] = base.with(texts[c]);
                }
                // The same deals and the same rival for each deal, for every child of the generation
                long dealSeed = rnd.nextLong();
                double[] fitness = play(children, texts, rivals, dealSeed, g, pool, dir);

                // Rank-based utilities (fitness shaping): the best children pull, the worst push away
                Integer[] order = new Integer[children.length];
                for (int c = 0; c < order.length; c++) order[c] = c;
                Arrays.sort(order, Comparator.comparingDouble((Integer c) -> -fitness[c]));
                double[] u = new double[children.length];
                double sum = 0;
                for (int r = 0; r < order.length; r++) sum += Math.max(0, Math.log(order.length / 2.0 + 1) - Math.log(r + 1));
                for (int r = 0; r < order.length; r++) u[order[r]] = Math.max(0, Math.log(order.length / 2.0 + 1) - Math.log(r + 1)) / sum - 1.0 / order.length;
                for (int i = 0; i < d; i++) {
                    double gm = 0, gs = 0;
                    for (int c = 0; c < children.length; c++) {
                        gm += u[c] * z[c][i];
                        gs += u[c] * (z[c][i] * z[c][i] - 1);
                    }
                    mean[i] = Math.max(0, mean[i] + etaMean * sigma[i] * gm);
                    sigma[i] *= Math.exp(etaSigma / 2 * gs);
                }
                // The best child joins the rivals (the last few only)
                int best = order[0];
                if (fitness[best] > 0.5) {
                    rivals.add(Map.entry("g" + g, children[best]));
                    while (rivals.size() > 2 + hallOfFame) rivals.remove(2);
                }

                Map<String, Object> line = new LinkedHashMap<>();
                line.put("generation", g);
                line.put("mean", vector(names, mean));
                line.put("sigma", vector(names, sigma));
                List<Map<String, Object>> kids = new ArrayList<>();
                for (int c = 0; c < children.length; c++) kids.add(Map.of("params", texts[c], "fitness", BotDuel.round(fitness[c])));
                line.put("children", kids);
                line.put("meanFitness", BotDuel.round(Arrays.stream(fitness).average().orElse(0)));
                line.put("bestFitness", BotDuel.round(fitness[best]));
                line.put("best", texts[best]);
                line.put("rivals", rivals.stream().map(Map.Entry::getKey).toList());
                line.put("seconds", Math.round((System.nanoTime() - t0) / 1e9));
                append(dir.resolve("generations.jsonl"), line);
                System.out.println(String.format(Locale.ROOT, "[evolve] gen %d | children %.3f avg, best %.3f (%s) | centre %s | %.0f s",
                        g, Arrays.stream(fitness).average().orElse(0), fitness[best], texts[best], vector(names, mean), (System.nanoTime() - t0) / 1e9));

                if (g % checkEvery == 0 || g == generations) {
                    // The centre against the original hard bot, on deals no generation saw
                    BotSettings centre = base.with(text(names, mean));
                    List<Map<String, Object>> games = BotRatchetRun.paired(centre, BotSettings.DEFAULT.with(HARD), "lookahead", BotMatch.DECKS, 2,
                            new Random(seed * 31 + g), pool, "check g" + g, g, dir);
                    long w = games.stream().filter(m -> "a".equals(m.get("winner"))).count(), l = games.stream().filter(m -> "b".equals(m.get("winner"))).count();
                    double[] ci = BotDuel.wilson(w, w + l);
                    Map<String, Object> check = new LinkedHashMap<>();
                    check.put("generation", g);
                    check.put("centre", text(names, mean));
                    check.put("wins", w);
                    check.put("losses", l);
                    check.put("winRate", BotDuel.round(w + l == 0 ? 0 : (double) w / (w + l)));
                    check.put("winLow95", BotDuel.round(ci[0]));
                    check.put("winHigh95", BotDuel.round(ci[1]));
                    append(dir.resolve("checks.jsonl"), check);
                    System.out.println(String.format(Locale.ROOT, "[evolve] check g%d: centre vs Difícil %d-%d (%.3f, 95%% %.3f-%.3f)", g, w, l, (double) w / Math.max(1, w + l), ci[0], ci[1]));
                }
            }
        } finally {
            pool.shutdown();
        }
    }

    /**
     * One generation's games: for each pair of decks a rival from the pool (in turn), both seats, every child on the
     * same deal. Returns each child's share of its games (a draw counts half).
     */
    private static double[] play(BotSettings[] children, String[] texts, List<Map.Entry<String, BotSettings>> rivals,
                                 long dealSeed, int generation, ExecutorService pool, Path dir) throws Exception {
        record Game(int child, String rival, String hostDeck, String guestDeck, boolean childHost, long seed) {}
        List<Game> plan = new ArrayList<>();
        int deal = 0;
        for (String x : BotMatch.DECKS) for (String y : BotMatch.DECKS) {
            String rival = rivals.get(deal % rivals.size()).getKey();
            long seed = dealSeed + deal++;
            for (int c = 0; c < children.length; c++) {
                plan.add(new Game(c, rival, x, y, true, seed));
                plan.add(new Game(c, rival, x, y, false, seed));
            }
        }
        Map<String, BotSettings> byName = new LinkedHashMap<>();
        rivals.forEach(e -> byName.put(e.getKey(), e.getValue()));
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        for (Game gm : plan) {
            futures.add(pool.submit(() -> {
                LookaheadBot child = new LookaheadBot(children[gm.child()]), rival = new LookaheadBot(byName.get(gm.rival()));
                BotMatch.Result r = gm.childHost() ? BotMatch.playSeeded(gm.hostDeck(), gm.guestDeck(), child, rival, gm.seed())
                        : BotMatch.playSeeded(gm.hostDeck(), gm.guestDeck(), rival, child, gm.seed());
                String seat = gm.childHost() ? "host" : "guest";
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("phase", "evolve");
                m.put("iteration", generation);
                m.put("child", gm.child());
                m.put("params", texts[gm.child()]);
                m.put("rival", gm.rival());
                m.put("seed", gm.seed());
                m.put("aDeck", gm.childHost() ? gm.hostDeck() : gm.guestDeck());
                m.put("bDeck", gm.childHost() ? gm.guestDeck() : gm.hostDeck());
                m.put("winner", r.winner() == null ? null : r.winner().equals(seat) ? "a" : "b");
                m.put("turns", r.turns());
                m.put("ms", r.millis());
                m.put("aPlays", r.plays().getOrDefault(seat, 0));
                m.put("aEffects", r.effects().getOrDefault(seat, 0));
                m.put("aThink", BotRatchetRun.thinking(child));
                return m;
            }));
        }
        double[] score = new double[children.length];
        int[] games = new int[children.length];
        StringBuilder sb = new StringBuilder();
        for (Future<Map<String, Object>> f : futures) {
            Map<String, Object> m = f.get();
            int c = (Integer) m.get("child");
            games[c]++;
            score[c] += "a".equals(m.get("winner")) ? 1 : m.get("winner") == null ? 0.5 : 0;
            sb.append(BotRatchetRun.JSON.writeValueAsString(m)).append(System.lineSeparator());
        }
        Files.writeString(dir.resolve("games.jsonl"), sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        for (int c = 0; c < score.length; c++) score[c] /= Math.max(1, games[c]);
        return score;
    }

    private static Map<String, Double> vector(List<String> names, double[] v) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < v.length; i++) m.put(names.get(i), BotDuel.round(v[i]));
        return m;
    }

    private static String text(List<String> names, double[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) sb.append(i == 0 ? "" : ",").append(names.get(i)).append('=').append(String.format(Locale.ROOT, "%.4f", v[i]));
        return sb.toString();
    }

    private static void append(Path file, Map<String, Object> line) throws Exception {
        Files.writeString(file, BotRatchetRun.JSON.writeValueAsString(line) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
