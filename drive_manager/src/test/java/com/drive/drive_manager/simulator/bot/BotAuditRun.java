package com.drive.drive_manager.simulator.bot;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Where does the bot misjudge? It plays itself (the server's settings, or {@code bot.audit.settings}) on every pair of
 * starter decks, {@code bot.audit.seeds} deals each, and keeps every decision it looks ahead for on its own turn: the
 * move, its card and target, the score it expected when its look-ahead ends (with turns=2, the start of its next turn)
 * and the score it found there (± the win score when the game ended first). A big drop is a misjudgment. Run with
 * {@code -Dbot.audit=true -Dtest=BotAuditRun}; kept in {@code <out>/<date>_audit/}: run.json, decisions.jsonl, games.jsonl.
 */
class BotAuditRun {

    private static final double WIN = 10_000;

    @Test
    void audit() throws Exception {
        assumeTrue(Boolean.getBoolean("bot.audit"), "runs only with -Dbot.audit=true");
        int seeds = Integer.getInteger("bot.audit.seeds", 8);
        int threads = Integer.getInteger("bot.audit.threads", Runtime.getRuntime().availableProcessors());
        String text = System.getProperty("bot.audit.settings", "").trim();
        if (text.isEmpty()) {
            try (InputStream in = BotRooms.class.getResourceAsStream(BotCatalog.SETTINGS_RESOURCE)) {
                text = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        }
        BotSettings settings = BotSettings.DEFAULT.with(text);
        Path dir = Path.of(System.getProperty("bot.audit.out", "target/bot-runs"))
                .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "_audit");
        Files.createDirectories(dir);
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("method", "audit: the bot against itself on every deck pair; each own-turn decision with the score expected and found");
        run.put("settings", text);
        run.put("seeds", seeds);
        run.put("startedAt", LocalDateTime.now().toString());
        Files.writeString(dir.resolve("run.json"), BotRatchetRun.JSON.writeValueAsString(run), StandardCharsets.UTF_8);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<Map<String, Object>>>> games = new ArrayList<>();
            int game = 0;
            for (int s = 1; s <= seeds; s++) {
                for (String x : BotMatch.DECKS) for (String y : BotMatch.DECKS) {
                    int id = ++game;
                    long seed = 9000L + s;
                    games.add(pool.submit(() -> play(id, seed, x, y, settings, dir)));
                }
            }
            StringBuilder sb = new StringBuilder();
            int decisions = 0;
            for (Future<List<Map<String, Object>>> f : games) {
                for (Map<String, Object> d : f.get()) {
                    sb.append(BotRatchetRun.JSON.writeValueAsString(d)).append(System.lineSeparator());
                    decisions++;
                }
            }
            Files.writeString(dir.resolve("decisions.jsonl"), sb.toString(), StandardCharsets.UTF_8);
            System.out.println("[audit] " + games.size() + " games, " + decisions + " decisions -> " + dir);
        } finally {
            pool.shutdown();
        }
    }

    /** One game; its decisions on each bot's own turn, each with the score found when its look-ahead ends. */
    private static List<Map<String, Object>> play(int id, long seed, String hostDeck, String guestDeck, BotSettings settings, Path dir) throws Exception {
        List<LookaheadBot.Decision> seen = new ArrayList<>();
        LookaheadBot host = new LookaheadBot(settings), guest = new LookaheadBot(settings);
        host.watch(seen::add);
        guest.watch(seen::add);
        BotMatch.Result r = BotMatch.playSeeded(hostDeck, guestDeck, host, guest, seed);
        // A new turn each time the turn changes hands between two decisions
        int[] turn = new int[seen.size()];
        for (int i = 1; i < seen.size(); i++) turn[i] = turn[i - 1] + (seen.get(i).turnOf().equals(seen.get(i - 1).turnOf()) ? 0 : 1);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < seen.size(); i++) {
            LookaheadBot.Decision d = seen.get(i);
            if (!d.me().equals(d.turnOf())) continue;
            // Its next turn: the first decision it takes on a later turn of its own
            Double found = null;
            for (int j = i + 1; j < seen.size() && found == null; j++) {
                LookaheadBot.Decision e = seen.get(j);
                if (turn[j] > turn[i] && e.me().equals(d.me()) && e.turnOf().equals(d.me())) found = e.now();
            }
            boolean ended = found == null;
            if (ended) found = r.winner() == null ? d.now() : r.winner().equals(d.me()) ? WIN : -WIN;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("game", id);
            m.put("seed", seed);
            m.put("seat", d.me());
            m.put("deck", "host".equals(d.me()) ? hostDeck : guestDeck);
            m.put("rivalDeck", "host".equals(d.me()) ? guestDeck : hostDeck);
            m.put("turn", turn[i]);
            m.put("type", d.type());
            m.put("card", d.card());
            m.put("target", d.target());
            m.put("candidates", d.candidates());
            m.put("index", d.index());
            m.put("now", BotDuel.round(d.now()));
            m.put("expected", BotDuel.round(d.expected()));
            m.put("found", BotDuel.round(found));
            m.put("ended", ended);
            m.put("won", d.me().equals(r.winner()));
            out.add(m);
        }
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("game", id);
        g.put("seed", seed);
        g.put("hostDeck", hostDeck);
        g.put("guestDeck", guestDeck);
        g.put("winner", r.winner());
        g.put("reason", r.reason());
        g.put("turns", r.turns());
        synchronized (BotAuditRun.class) {
            Files.writeString(dir.resolve("games.jsonl"), BotRatchetRun.JSON.writeValueAsString(g) + System.lineSeparator(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        }
        return out;
    }
}
