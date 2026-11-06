package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.GameEvent;
import com.drive.drive_manager.simulator.room.GameRoom;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * How two bots play differently: {@code bot.compare.a} against {@code bot.compare.b} (settings over the defaults) on
 * every pair of starter decks, each deal from both seats; per bot, the game's events counted by type (summons, plays,
 * attacks, damage, tributes…), its hand at the start of its turns and its wins, per deck. Run with
 * {@code -Dbot.compare=true -Dtest=BotCompareRun} ({@code bot.compare.seeds}, {@code bot.compare.threads}).
 */
class BotCompareRun {

    private static final List<String> TYPES = List.of(GameEvent.NORMAL_SUMMON, GameEvent.SPECIAL_SUMMON, GameEvent.EFFECT_SUMMON,
            GameEvent.PLAY, GameEvent.EFFECT_ACTIVATED, GameEvent.ATTACK_DECLARED, GameEvent.BLOCK, GameEvent.LIFE_DAMAGE,
            GameEvent.DESTROY, GameEvent.TRIBUTE, GameEvent.DRAW, GameEvent.EFFECT_DECLINED);

    @Test
    void compare() throws Exception {
        assumeTrue(Boolean.getBoolean("bot.compare"), "runs only with -Dbot.compare=true");
        BotSettings a = BotSettings.DEFAULT.with(System.getProperty("bot.compare.a", ""));
        BotSettings b = BotSettings.DEFAULT.with(System.getProperty("bot.compare.b", ""));
        int seeds = Integer.getInteger("bot.compare.seeds", 1);
        ExecutorService pool = Executors.newFixedThreadPool(Integer.getInteger("bot.compare.threads", 8));
        Map<String, Map<String, Double>> sums = new ConcurrentHashMap<>();
        List<Future<?>> games = new ArrayList<>();
        for (int s = 1; s <= seeds; s++) for (String x : BotMatch.DECKS) for (String y : BotMatch.DECKS) for (boolean aHosts : List.of(true, false)) {
            long seed = s * 1000L + BotMatch.DECKS.indexOf(x) * 10 + BotMatch.DECKS.indexOf(y);
            games.add(pool.submit(() -> {
                GameRoom[] last = {null};
                Map<String, int[]> hands = new HashMap<>();
                BotPlayer host = new LookaheadBot(aHosts ? a : b), guest = new LookaheadBot(aHosts ? b : a);
                BotMatch.Result r = BotMatch.playWatching(x, y, host, guest, room -> {
                    last[0] = room;
                    String turn = room.getCurrentTurnUserId();
                    if (turn != null && room.getPlayer(turn) != null) {
                        int[] h = hands.computeIfAbsent(turn, k -> new int[2]);
                        h[0] += room.getPlayer(turn).getHand().size();
                        h[1]++;
                    }
                });
                if (last[0] == null) return null;
                for (String seat : List.of("host", "guest")) {
                    boolean isA = seat.equals("host") == aHosts;
                    String who = isA ? "A" : "B", deck = seat.equals("host") ? x : y;
                    Map<String, Double> m = sums.computeIfAbsent(who, k -> new ConcurrentHashMap<>());
                    Map<String, Double> md = sums.computeIfAbsent(who + " " + deck, k -> new ConcurrentHashMap<>());
                    for (Map<String, Double> t : List.of(m, md)) {
                        t.merge("games", 1.0, Double::sum);
                        t.merge("wins", seat.equals(r.winner()) ? 1.0 : 0.0, Double::sum);
                        t.merge("turns", (double) r.turns(), Double::sum);
                        int[] h = hands.getOrDefault(seat, new int[]{0, 1});
                        t.merge("hand@turn", h[1] == 0 ? 0 : (double) h[0] / h[1], Double::sum);
                        for (GameEvent e : last[0].getTimeline()) {
                            if (seat.equals(e.getPlayerId()) && TYPES.contains(e.getType())) t.merge(e.getType(), 1.0, Double::sum);
                        }
                    }
                }
                return null;
            }));
        }
        for (Future<?> g : games) g.get();
        pool.shutdown();
        List<String> cols = new ArrayList<>(List.of("wins", "turns", "hand@turn"));
        cols.addAll(TYPES);
        System.out.println("[cmp] who | " + String.join(" | ", cols) + "  (per game)");
        new TreeMap<>(sums).forEach((who, m) -> {
            double n = m.getOrDefault("games", 1.0);
            StringBuilder sb = new StringBuilder("[cmp] " + who + " (" + (int) n + ")");
            for (String c : cols) sb.append(" | ").append(String.format(Locale.ROOT, "%.2f", m.getOrDefault(c, 0.0) / n));
            System.out.println(sb);
        });
    }
}
