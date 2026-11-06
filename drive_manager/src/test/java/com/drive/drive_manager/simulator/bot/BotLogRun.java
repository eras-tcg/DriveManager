package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.room.GameLogEntry;
import com.drive.drive_manager.simulator.room.GameRoom;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The history of whole games, to read: {@code bot.log.a} against {@code bot.log.b} on decks {@code bot.log.decks}
 * ("st4,st4"), {@code bot.log.seeds} deals from both seats, deciding with no time limit (playSeeded). Each game's
 * history goes to {@code <bot.log.out>/game-<seed>-<A seat>.txt}; a line per game with who won.
 */
class BotLogRun {

    @Test
    void log() throws Exception {
        assumeTrue(Boolean.getBoolean("bot.log"), "runs only with -Dbot.log=true");
        BotSettings a = BotSettings.DEFAULT.with(System.getProperty("bot.log.a", ""));
        BotSettings b = BotSettings.DEFAULT.with(System.getProperty("bot.log.b", ""));
        String[] decks = System.getProperty("bot.log.decks", "st4,st4").split(",");
        int seeds = Integer.getInteger("bot.log.seeds", 4);
        Path out = Path.of(System.getProperty("bot.log.out", "target/bot-logs"));
        Files.createDirectories(out);
        ExecutorService pool = Executors.newFixedThreadPool(Integer.getInteger("bot.log.threads", 8));
        List<Future<String>> games = new ArrayList<>();
        for (int s = 1; s <= seeds; s++) for (boolean aHosts : List.of(true, false)) {
            long seed = 7000 + s;
            games.add(pool.submit(() -> {
                GameRoom[] last = {null};
                BotPlayer host = new LookaheadBot(aHosts ? a : b), guest = new LookaheadBot(aHosts ? b : a);
                BotMatch.Result r = BotMatch.playSeededWatching(decks[0], decks[1], host, guest, seed, room -> last[0] = room);
                String aSeat = aHosts ? "host" : "guest";
                StringBuilder sb = new StringBuilder("A=" + aSeat + " (" + System.getProperty("bot.log.a") + ")\nwinner=" + r.winner() + " " + r.reason() + " turns=" + r.turns() + "\n");
                if (last[0] != null) for (GameLogEntry e : last[0].getEventLog()) sb.append(e.getText()).append('\n');
                Files.writeString(out.resolve("game-" + seed + "-A" + aSeat + ".txt"), sb.toString(), StandardCharsets.UTF_8);
                return seed + " A=" + aSeat + " winner=" + (aSeat.equals(r.winner()) ? "A" : "B") + " turns=" + r.turns();
            }));
        }
        for (Future<String> g : games) System.out.println("[log] " + g.get());
        pool.shutdown();
    }
}
