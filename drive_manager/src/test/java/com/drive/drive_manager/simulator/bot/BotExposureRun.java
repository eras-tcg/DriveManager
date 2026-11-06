package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.PlayerState;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * In which zones each card of the arena's decks is seen during games, at every change of turn, from both sides.
 * Run with {@code -Dbot.exposure=true -Dtest=BotExposureRun}; writes {@code exposure.csv} in {@code bot.exposure.out}:
 * <ul>
 *   <li>{@code field, hand, tribute, discard}: share of all the tables where at least one copy is there. Zero means the
 *   card never gets there: its bias in that zone changes no score, it only drifts (the tuner's minExposure reads these);</li>
 *   <li>{@code copies}: copies of the card in its deck; {@code ownTables}: tables of a side playing that deck;</li>
 *   <li>{@code fieldCopies…discardCopies}: copies there on average, per table of a side playing its deck. A card's bias
 *   counts once per copy, so this is how much it weighs in the score;</li>
 *   <li>{@code fieldPerCopy…discardPerCopy}: the same divided by its copies, to compare cards whatever their copies.</li>
 * </ul>
 */
class BotExposureRun {

    private static final List<String> ZONES = List.of("field", "hand", "tribute", "discard");
    private static final int Z = ZONES.size();

    /** Counts of one card: [0..Z) tables with a copy there (all sides), [Z..2Z) copies there (sides of its deck). */
    private static long[] counts() {
        return new long[2 * Z];
    }

    @Test
    void exposure() throws Exception {
        assumeTrue(Boolean.getBoolean("bot.exposure"), "runs only with -Dbot.exposure=true");
        int games = Integer.getInteger("bot.exposure.games", 4);
        int threads = Integer.getInteger("bot.exposure.threads", 4);
        Path out = Path.of(System.getProperty("bot.exposure.out", "target/bot-runs")).resolve("exposure.csv");
        Map<String, String> deckOf = BotMatch.deckOfCards();
        Map<String, Integer> copies = BotMatch.copiesOfCards();
        Map<String, long[]> seen = new TreeMap<>();
        // Tables in all, and tables of a side playing each deck
        long[] tables = {0};
        Map<String, Long> deckTables = new HashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        record Part(Map<String, long[]> seen, long tables, Map<String, Long> deckTables) {}
        List<Future<Part>> futures = new ArrayList<>();
        for (String a : BotMatch.DECKS) for (String b : BotMatch.DECKS) for (int g = 0; g < games; g++) {
            futures.add(pool.submit(() -> {
                Map<String, long[]> local = new TreeMap<>();
                Map<String, Long> localDecks = new HashMap<>();
                long[] n = {0};
                BotMatch.playWatching(a, b, new HeuristicBot(), new HeuristicBot(), room -> {
                    for (PlayerState p : room.getPlayers().values()) {
                        n[0]++;
                        String deck = p.getDeckName() == null ? "" : p.getDeckName().toLowerCase(Locale.ROOT);
                        localDecks.merge(deck, 1L, Long::sum);
                        List<List<CardSlot>> zones = List.of(p.getField(), p.getHand(), p.getTributeZone(), p.getDiscardPile());
                        for (int z = 0; z < Z; z++) {
                            Map<String, Integer> here = new HashMap<>();
                            for (CardSlot c : zones.get(z)) if (c.getCardName() != null) here.merge(BotSettings.cardKey(c.getCardName()), 1, Integer::sum);
                            for (Map.Entry<String, Integer> e : here.entrySet()) {
                                long[] k = local.computeIfAbsent(e.getKey(), x -> counts());
                                k[z]++;
                                if (deck.equals(deckOf.get(e.getKey()))) k[Z + z] += e.getValue();
                            }
                        }
                    }
                });
                return new Part(local, n[0], localDecks);
            }));
        }
        for (Future<Part> f : futures) {
            Part part = f.get();
            tables[0] += part.tables();
            part.deckTables().forEach((d, n) -> deckTables.merge(d, n, Long::sum));
            part.seen().forEach((name, c) -> {
                long[] all = seen.computeIfAbsent(name, k -> counts());
                for (int i = 0; i < c.length; i++) all[i] += c[i];
            });
        }
        pool.shutdown();
        StringBuilder csv = new StringBuilder("card," + String.join(",", ZONES) + ",tables,copies,ownTables");
        for (String z : ZONES) csv.append(',').append(z).append("Copies");
        for (String z : ZONES) csv.append(',').append(z).append("PerCopy");
        csv.append('\n');
        for (String card : BotMatch.cardNames()) {
            long[] c = seen.getOrDefault(card, counts());
            long own = deckTables.getOrDefault(deckOf.get(card), 0L);
            int k = copies.getOrDefault(card, 1);
            csv.append(card);
            for (int z = 0; z < Z; z++) csv.append(',').append(fmt((double) c[z] / Math.max(1, tables[0])));
            csv.append(',').append(tables[0]).append(',').append(k).append(',').append(own);
            for (int z = 0; z < Z; z++) csv.append(',').append(fmt((double) c[Z + z] / Math.max(1, own)));
            for (int z = 0; z < Z; z++) csv.append(',').append(fmt((double) c[Z + z] / Math.max(1, own) / k));
            csv.append('\n');
        }
        Files.createDirectories(out.getParent());
        Files.writeString(out, csv.toString(), StandardCharsets.UTF_8);
        System.out.println("Exposure of " + BotMatch.cardNames().size() + " cards over " + tables[0] + " tables -> " + out.toAbsolutePath());
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
