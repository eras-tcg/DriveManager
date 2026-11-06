package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.GameEvent;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Utilities the bot plays for nothing: the champion against itself on every pair of starter decks; for each utility it
 * plays, the events of its resolution. One with no event but its own activation, resolution and trip to the Descarte
 * did nothing. Prints, per card, how often it was played and how often for nothing, with the first few cases' events.
 * Run with {@code -Dbot.dud=true -Dtest=BotDudRun} ({@code bot.dud.settings}, {@code bot.dud.seeds}).
 */
class BotDudRun {

    private static final Set<String> FRAME = Set.of(GameEvent.EFFECT_ACTIVATED, GameEvent.EFFECT_RESOLVED, GameEvent.TARGET, GameEvent.PLAY);

    @Test
    void duds() throws Exception {
        assumeTrue(Boolean.getBoolean("bot.dud"), "runs only with -Dbot.dud=true");
        BotSettings settings = BotSettings.DEFAULT.with(System.getProperty("bot.dud.settings", ""));
        int seeds = Integer.getInteger("bot.dud.seeds", 2);
        Usefulness.diffs = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(Integer.getInteger("bot.dud.threads", 8));
        Map<String, int[]> counts = new ConcurrentSkipListMap<>();
        List<String> samples = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> games = new ArrayList<>();
        for (int s = 0; s < seeds; s++) for (String x : BotMatch.DECKS) for (String y : BotMatch.DECKS) {
            games.add(pool.submit(() -> {
                long[] seen = {0};
                BotMatch.playWatching(x, y, new LookaheadBot(settings), new LookaheadBot(settings), room -> scan(room, seen, counts, samples, x + " vs " + y));
                return null;
            }));
        }
        for (Future<?> g : games) g.get();
        pool.shutdown();
        System.out.println("[dud] card | played | for nothing");
        counts.forEach((card, n) -> System.out.println("[dud] " + card + " | " + n[0] + " | " + n[1]));
        System.out.println("[dud] usefulness checks " + Usefulness.ASKED + ", nothing " + Usefulness.NOTHING);
        Usefulness.BY_CARD.forEach((k, n) -> System.out.println("[dud-yes] " + k + " something " + n[0] + " nothing " + n[1]));
        Usefulness.diffs.forEach(d -> System.out.println("[dud-diff] " + d));
        samples.stream().limit(25).forEach(s -> System.out.println("[dud-case] " + s));
    }

    /** The resolutions of utilities since the last look, each counted (and kept as a sample when it did nothing). */
    private static void scan(GameRoom room, long[] seen, Map<String, int[]> counts, List<String> samples, String game) {
        List<GameEvent> events = room.getTimeline().stream().filter(e -> e.getSeq() > seen[0]).toList();
        if (events.isEmpty()) return;
        // Only resolutions that ended: the last events may still be open
        Map<String, List<GameEvent>> byResolution = new LinkedHashMap<>();
        Set<String> ended = new HashSet<>();
        for (GameEvent e : events) {
            if (e.getResolutionId() == null) continue;
            byResolution.computeIfAbsent(e.getResolutionId(), k -> new ArrayList<>()).add(e);
            if (GameEvent.EFFECT_RESOLVED.equals(e.getType())) ended.add(e.getResolutionId());
        }
        long last = seen[0];
        for (Map.Entry<String, List<GameEvent>> r : byResolution.entrySet()) {
            if (!ended.contains(r.getKey())) continue;
            GameEvent first = r.getValue().get(0);
            CardSlot card = find(room, first.getCardInstanceId());
            if (card == null || !"utility".equals(card.getCardType())) continue;
            boolean nothing = r.getValue().stream().allMatch(e -> FRAME.contains(e.getType())
                    || (Objects.equals(e.getCardInstanceId(), first.getCardInstanceId()) && "discard".equalsIgnoreCase(String.valueOf(e.getToZone()))));
            int[] n = counts.computeIfAbsent(card.getCardName(), k -> new int[2]);
            synchronized (n) { n[0]++; if (nothing) n[1]++; }
            if (nothing) {
                StringBuilder sb = new StringBuilder(game + " | " + card.getCardName() + " (" + first.getEffectId() + ") by " + first.getPlayerId() + ":");
                r.getValue().forEach(e -> sb.append(' ').append(e.getType()).append(e.getData() == null ? "" : e.getData().toString()));
                samples.add(sb.toString());
            }
        }
        for (GameEvent e : events) if (e.getResolutionId() == null || ended.contains(e.getResolutionId()) || !byResolution.containsKey(e.getResolutionId())) last = Math.max(last, e.getSeq());
        seen[0] = last;
    }

    private static CardSlot find(GameRoom room, String instanceId) {
        if (instanceId == null) return null;
        for (PlayerState p : room.getPlayers().values()) {
            for (List<CardSlot> zone : List.of(p.getHand(), p.getField(), p.getDiscardPile(), p.getTributeZone(), p.getDeck(), p.getLifeStack())) {
                for (CardSlot c : zone) if (instanceId.equals(c.getInstanceId())) return c;
            }
        }
        return null;
    }
}
