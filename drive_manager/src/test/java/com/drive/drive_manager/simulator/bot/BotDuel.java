package com.drive.drive_manager.simulator.bot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * A match between two look-ahead bots, A and B: every pair of decks, from both seats, {@code perPairAndSeat} times,
 * on several threads. Fair to both: each plays each deck and each seat as often as the other. Several matches can
 * share one pool of threads ({@link #start}): their games are queued together, so no thread waits for the last
 * game of one match while others could be played.
 */
final class BotDuel {

    private BotDuel() {}

    /**
     * How the match went, from A's side.
     *
     * @param games one line per game: decks, seats, winner ("a", "b" or null), reason, turns, problems and thinking
     */
    record Outcome(int played, long wins, long losses, double winRate, double low95, double high95, long aConceded,
                   long stuck, double aOutOfTimeShare, double aAvgDecisionMs, List<Map<String, Object>> games) {

        /** The numbers of the match, without its games (for the run's files). */
        Map<String, Object> summary() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("games", played);
            m.put("wins", wins);
            m.put("losses", losses);
            m.put("unfinished", played - wins - losses);
            m.put("winRate", round(winRate));
            m.put("winLow95", round(low95));
            m.put("winHigh95", round(high95));
            m.put("conceded", aConceded);
            m.put("stuck", stuck);
            m.put("outOfTimeShare", round(aOutOfTimeShare));
            m.put("avgDecisionMs", round(aAvgDecisionMs));
            return m;
        }

        /** A's score in [-1, 1]: (wins − losses) / games. */
        double score() {
            return played == 0 ? 0 : (double) (wins - losses) / played;
        }
    }

    /** A match on a pool of its own. */
    static Outcome play(Supplier<LookaheadBot> a, Supplier<LookaheadBot> b, List<String> decks, int perPairAndSeat, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            return start(a, b, decks, perPairAndSeat, pool).get();
        } finally {
            pool.shutdown();
        }
    }

    /** A match whose games are queued on the shared {@code pool} right away; {@link Pending#get} waits for them. */
    static Pending start(Supplier<LookaheadBot> a, Supplier<LookaheadBot> b, List<String> decks, int perPairAndSeat, ExecutorService pool) {
        record Seat(String aDeck, String bDeck, boolean aHost) {}
        List<Seat> seats = new ArrayList<>();
        for (String x : decks) for (String y : decks) for (int g = 0; g < perPairAndSeat; g++) {
            seats.add(new Seat(x, y, true));
            seats.add(new Seat(x, y, false));
        }
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        for (Seat s : seats) {
                futures.add(pool.submit(() -> {
                    LookaheadBot botA = a.get();
                    LookaheadBot botB = b.get();
                    BotMatch.Result r = s.aHost() ? BotMatch.play(s.aDeck(), s.bDeck(), botA, botB) : BotMatch.play(s.bDeck(), s.aDeck(), botB, botA);
                    String aSeat = s.aHost() ? "host" : "guest";
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("aDeck", s.aDeck());
                    m.put("bDeck", s.bDeck());
                    m.put("aSeat", aSeat);
                    m.put("winner", r.winner() == null ? null : r.winner().equals(aSeat) ? "a" : "b");
                    m.put("reason", r.reason());
                    m.put("turns", r.turns());
                    m.put("aConceded", aSeat.equals(r.conceded()));
                    m.put("stuckAt", r.stuckAt());
                    m.put("ms", r.millis());
                    m.put("aPlays", r.plays().get(aSeat));
                    m.put("bPlays", r.plays().get(s.aHost() ? "guest" : "host"));
                    LookaheadBot.Stats st = botA.stats();
                    m.put("aDecisions", st.decisions());
                    m.put("aOutOfTime", st.outOfTime());
                    m.put("aAvgMs", st.decisions() == 0 ? 0 : st.totalNanos() / 1e6 / st.decisions());
                    return m;
                }));
        }
        return new Pending(futures);
    }

    /** A match being played. */
    record Pending(List<Future<Map<String, Object>>> futures) {
        Outcome get() throws Exception {
            List<Map<String, Object>> games = new ArrayList<>();
            for (Future<Map<String, Object>> f : futures) games.add(f.get());
            return outcome(games);
        }
    }

    private static Outcome outcome(List<Map<String, Object>> games) {
        long wins = games.stream().filter(m -> "a".equals(m.get("winner"))).count();
        long losses = games.stream().filter(m -> "b".equals(m.get("winner"))).count();
        long decisions = games.stream().mapToLong(m -> ((Number) m.get("aDecisions")).longValue()).sum();
        long outOfTime = games.stream().mapToLong(m -> ((Number) m.get("aOutOfTime")).longValue()).sum();
        double[] ci = wilson(wins, wins + losses);
        return new Outcome(games.size(), wins, losses, wins + losses == 0 ? 0 : (double) wins / (wins + losses), ci[0], ci[1],
                games.stream().filter(m -> Boolean.TRUE.equals(m.get("aConceded"))).count(),
                games.stream().filter(m -> m.get("stuckAt") != null).count(),
                decisions == 0 ? 0 : (double) outOfTime / decisions,
                games.stream().mapToDouble(m -> ((Number) m.get("aAvgMs")).doubleValue()).average().orElse(0), games);
    }

    /** Wilson score interval at 95 % for {@code wins} of {@code n}. */
    static double[] wilson(long wins, long n) {
        if (n == 0) return new double[]{0, 0};
        double z = 1.96;
        double p = (double) wins / n;
        double center = (p + z * z / (2 * n)) / (1 + z * z / n);
        double half = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / (1 + z * z / n);
        return new double[]{Math.max(0, center - half), Math.min(1, center + half)};
    }

    static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
