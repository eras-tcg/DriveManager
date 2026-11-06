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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The bot arena: many games between bot variants, played on several threads at once, each one saved as it ends so
 * the results can be studied later (and a run cut short keeps what it played). Off in the normal build; run it with
 * {@code local-dev/bot-arena.sh} or {@code -Dbot.arena=true -Dtest=BotArenaRun}.
 * <p>
 * Settings (system properties):
 * <ul>
 *   <li>{@code bot.arena.matchups}: "A vs B" pairs separated by ";", each bot as {@link BotMatch#bot} reads it
 *   ("lookahead:turns=2 vs heuristic; heuristic vs passive");</li>
 *   <li>{@code bot.arena.games}: games of each matchup per pair of decks and seat (default 2: A as host, then as guest, twice each);</li>
 *   <li>{@code bot.arena.decks}: decks to use, separated by "," (default all starter decks);</li>
 *   <li>{@code bot.arena.threads}: games at once (default one per processor);</li>
 *   <li>{@code bot.arena.out}: folder for the runs (default target/bot-runs); {@code bot.arena.label}: name of the run.</li>
 * </ul>
 * Each run is a folder {@code <date>_<label>} with {@code run.json} (its settings and totals), {@code games.jsonl}
 * (one line per game) and {@code summary.csv} / {@code by-deck.csv} (wins of A by matchup, and by matchup and decks).
 */
class BotArenaRun {

    private static final ObjectMapper JSON = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private record Matchup(String a, String b) {
        String name() {
            return a + " vs " + b;
        }
    }

    private record Game(Matchup matchup, int index, String aDeck, String bDeck, boolean aHost) {}

    @Test
    void arena() throws Exception {
        assumeTrue(Boolean.getBoolean("bot.arena"), "the arena runs only with -Dbot.arena=true");
        List<Matchup> matchups = Arrays.stream(System.getProperty("bot.arena.matchups", "lookahead vs heuristic").split(";"))
                .map(String::trim).filter(s -> !s.isEmpty()).map(s -> {
                    String[] ab = s.split("\\s+vs\\s+");
                    if (ab.length != 2) throw new IllegalArgumentException("A matchup is \"A vs B\": " + s);
                    BotMatch.bot(ab[0]);
                    BotMatch.bot(ab[1]);
                    return new Matchup(ab[0].trim(), ab[1].trim());
                }).toList();
        int games = Integer.getInteger("bot.arena.games", 2);
        List<String> decks = Arrays.stream(System.getProperty("bot.arena.decks", String.join(",", BotMatch.DECKS)).split(","))
                .map(String::trim).toList();
        int threads = Integer.getInteger("bot.arena.threads", Runtime.getRuntime().availableProcessors());
        String label = System.getProperty("bot.arena.label", "arena").replaceAll("[^A-Za-z0-9._-]", "-");
        Path dir = Path.of(System.getProperty("bot.arena.out", "target/bot-runs"))
                .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "_" + label);
        Files.createDirectories(dir);

        List<Game> plan = new ArrayList<>();
        for (Matchup m : matchups) {
            for (String a : decks) {
                for (String b : decks) {
                    for (int g = 0; g < games; g++) {
                        plan.add(new Game(m, plan.size(), a, b, true));
                        plan.add(new Game(m, plan.size(), a, b, false));
                    }
                }
            }
        }

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("label", label);
        run.put("startedAt", Instant.now().toString());
        run.put("matchups", matchups.stream().map(Matchup::name).toList());
        run.put("gamesPerDeckPairAndSeat", games);
        run.put("decks", decks);
        run.put("threads", threads);
        run.put("plannedGames", plan.size());
        run.put("processors", Runtime.getRuntime().availableProcessors());
        run.put("lookaheadDefaults", BotSettings.DEFAULT.asMap());
        write(dir.resolve("run.json"), run);

        System.out.println("Bot arena: " + plan.size() + " games on " + threads + " threads -> " + dir.toAbsolutePath());
        List<Map<String, Object>> records = new ArrayList<>();
        AtomicInteger done = new AtomicInteger();
        long start = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try (BufferedWriter out = Files.newBufferedWriter(dir.resolve("games.jsonl"), StandardCharsets.UTF_8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (Game game : plan) {
                futures.add(pool.submit(() -> {
                    Map<String, Object> record = play(game);
                    synchronized (out) {
                        records.add(record);
                        try {
                            out.write(JSON.writeValueAsString(record));
                            out.newLine();
                            out.flush();
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }
                    int n = done.incrementAndGet();
                    if (n % 25 == 0 || n == plan.size()) {
                        System.out.printf("  %d/%d games, %.0f s%n", n, plan.size(), (System.nanoTime() - start) / 1e9);
                    }
                }));
            }
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdown();
        }

        List<Map<String, Object>> summary = summary(matchups, records, null);
        List<Map<String, Object>> byDeck = new ArrayList<>();
        for (String a : decks) for (String b : decks) byDeck.addAll(summary(matchups, records, a + "|" + b));
        csv(dir.resolve("summary.csv"), summary);
        csv(dir.resolve("by-deck.csv"), byDeck);
        run.put("finishedAt", Instant.now().toString());
        run.put("seconds", (System.nanoTime() - start) / 1_000_000_000L);
        run.put("playedGames", records.size());
        run.put("summary", summary);
        write(dir.resolve("run.json"), run);
        summary.forEach(row -> System.out.println("  " + row));
    }

    // ------------------------------------------------------------------ One game

    private static Map<String, Object> play(Game game) {
        BotPlayer a = BotMatch.bot(game.matchup().a());
        BotPlayer b = BotMatch.bot(game.matchup().b());
        String hostDeck = game.aHost() ? game.aDeck() : game.bDeck();
        String guestDeck = game.aHost() ? game.bDeck() : game.aDeck();
        String aSeat = game.aHost() ? "host" : "guest";
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("matchup", game.matchup().name());
        r.put("game", game.index());
        r.put("a", game.matchup().a());
        r.put("b", game.matchup().b());
        r.put("aDeck", game.aDeck());
        r.put("bDeck", game.bDeck());
        r.put("aSeat", aSeat);
        r.put("thread", Thread.currentThread().getName());
        try {
            BotMatch.Result result = game.aHost() ? BotMatch.play(hostDeck, guestDeck, a, b) : BotMatch.play(hostDeck, guestDeck, b, a);
            r.put("winner", result.winner() == null ? null : result.winner().equals(aSeat) ? "a" : "b");
            r.put("reason", result.reason());
            r.put("aFirst", aSeat.equals(result.first()));
            r.put("turns", result.turns());
            r.put("actions", result.actions());
            r.put("refused", result.refused());
            r.put("conceded", result.conceded() == null ? null : result.conceded().equals(aSeat) ? "a" : "b");
            r.put("stuckAt", result.stuckAt());
            r.put("ms", result.millis());
            String bSeat = aSeat.equals("host") ? "guest" : "host";
            r.put("aEnd", result.end().get(aSeat));
            r.put("bEnd", result.end().get(bSeat));
            r.put("aPlays", result.plays().get(aSeat));
            r.put("bPlays", result.plays().get(bSeat));
        } catch (RuntimeException e) {
            r.put("error", e.toString());
        }
        r.put("aThinking", thinking(a));
        r.put("bThinking", thinking(b));
        r.put("finishedAt", Instant.now().toString());
        return r;
    }

    /** What a look-ahead bot's decisions cost in the game (null for the others). */
    private static Map<String, Object> thinking(BotPlayer bot) {
        if (!(bot instanceof LookaheadBot ahead)) return null;
        LookaheadBot.Stats s = ahead.stats();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("decisions", s.decisions());
        m.put("avgMs", s.decisions() == 0 ? 0 : s.totalNanos() / 1e6 / s.decisions());
        m.put("maxMs", s.maxNanos() / 1e6);
        m.put("outOfTime", s.outOfTime());
        m.put("movesTried", s.movesTried());
        return m;
    }

    // ------------------------------------------------------------------ Summaries

    /** Per matchup (and per pair of decks, when {@code decks} is "aDeck|bDeck"): A's wins, with a 95 % interval. */
    private static List<Map<String, Object>> summary(List<Matchup> matchups, List<Map<String, Object>> records, String decks) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Matchup m : matchups) {
            List<Map<String, Object>> games = records.stream().filter(r -> m.name().equals(r.get("matchup")))
                    .filter(r -> decks == null || decks.equals(r.get("aDeck") + "|" + r.get("bDeck"))).toList();
            if (games.isEmpty()) continue;
            long aWins = games.stream().filter(r -> "a".equals(r.get("winner"))).count();
            long bWins = games.stream().filter(r -> "b".equals(r.get("winner"))).count();
            long decided = aWins + bWins;
            double rate = decided == 0 ? 0 : (double) aWins / decided;
            double[] ci = wilson(aWins, decided);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("matchup", m.name());
            if (decks != null) {
                row.put("aDeck", decks.split("\\|")[0]);
                row.put("bDeck", decks.split("\\|")[1]);
            }
            row.put("games", games.size());
            row.put("aWins", aWins);
            row.put("bWins", bWins);
            row.put("unfinished", games.size() - decided);
            row.put("aWinRate", round(rate));
            row.put("aWinRateLow95", round(ci[0]));
            row.put("aWinRateHigh95", round(ci[1]));
            row.put("aWinRateAsHost", round(rateWhere(games, "host")));
            row.put("aWinRateAsGuest", round(rateWhere(games, "guest")));
            row.put("byLives", games.stream().filter(r -> "lives".equals(r.get("reason"))).count());
            row.put("byDeck", games.stream().filter(r -> "deck".equals(r.get("reason"))).count());
            row.put("conceded", games.stream().filter(r -> r.get("conceded") != null).count());
            row.put("errors", games.stream().filter(r -> r.get("error") != null).count());
            row.put("avgTurns", round(games.stream().mapToDouble(r -> ((Number) r.getOrDefault("turns", 0)).doubleValue()).average().orElse(0)));
            row.put("avgGameMs", round(games.stream().mapToDouble(r -> ((Number) r.getOrDefault("ms", 0)).doubleValue()).average().orElse(0)));
            rows.add(row);
        }
        return rows;
    }

    private static double rateWhere(List<Map<String, Object>> games, String aSeat) {
        List<Map<String, Object>> seat = games.stream().filter(r -> aSeat.equals(r.get("aSeat")) && r.get("winner") != null).toList();
        return seat.isEmpty() ? 0 : (double) seat.stream().filter(r -> "a".equals(r.get("winner"))).count() / seat.size();
    }

    /** Wilson score interval at 95 % for {@code wins} of {@code n}. */
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

    private static void csv(Path file, List<Map<String, Object>> rows) throws IOException {
        if (rows.isEmpty()) return;
        StringBuilder sb = new StringBuilder(String.join(",", rows.get(0).keySet())).append('\n');
        for (Map<String, Object> row : rows) {
            sb.append(String.join(",", row.values().stream().map(v -> {
                String s = String.valueOf(v);
                return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
            }).toList())).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }
}
