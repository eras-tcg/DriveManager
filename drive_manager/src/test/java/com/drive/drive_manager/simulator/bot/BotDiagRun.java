package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.CardEffects;
import com.drive.drive_manager.simulator.engine.EffectScript;
import com.drive.drive_manager.simulator.engine.GameEvent;
import com.drive.drive_manager.simulator.engine.LegalActions;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What the bot leaves on the table, measured over its games against itself on every pair of starter decks
 * ({@code bot.diag.settings}, {@code bot.diag.seeds} deals): turns with no damage to anyone (and runs of 3 or more:
 * a stalled board); turns it held a removal it could play (an effect that destroys an enemy) with enemies on the
 * field and did not use it; creatures it summoned normally although they have a special summon. Run with
 * {@code -Dbot.diag=true -Dtest=BotDiagRun}.
 */
class BotDiagRun {

    @Test
    void diag() throws Exception {
        assumeTrue(Boolean.getBoolean("bot.diag"), "runs only with -Dbot.diag=true");
        BotSettings s = BotSettings.DEFAULT.with(System.getProperty("bot.diag.settings", ""));
        int seeds = Integer.getInteger("bot.diag.seeds", 1);
        ExecutorService pool = Executors.newFixedThreadPool(Integer.getInteger("bot.diag.threads", 8));
        Map<String, Long> n = new ConcurrentSkipListMap<>();
        Map<String, Long> byDeck = new ConcurrentSkipListMap<>();
        Map<String, Long> removals = new ConcurrentSkipListMap<>(), hardplays = new ConcurrentSkipListMap<>();
        List<Future<?>> games = new ArrayList<>();
        for (int k = 1; k <= seeds; k++) for (String x : BotMatch.DECKS) for (String y : BotMatch.DECKS) {
            long seed = 9000L + k * 100 + BotMatch.DECKS.indexOf(x) * 10 + BotMatch.DECKS.indexOf(y);
            games.add(pool.submit(() -> {
                Map<String, String> deckOf = Map.of("host", x, "guest", y);
                // At each turn's start: the turn's player, where the timeline was, the removals it could play
                Object[] at = {null, 0, Set.<String>of(), Set.<String>of()};
                int[] quiet = {0};
                BotMatch.playSeededWatching(x, y, new LookaheadBot(s), new LookaheadBot(s), seed, room -> {
                    List<GameEvent> tl = room.getTimeline();
                    String prev = (String) at[0];
                    if (prev != null) {
                        List<GameEvent> turn = tl.subList(Math.min((int) at[1], tl.size()), tl.size());
                        boolean damage = turn.stream().anyMatch(e -> GameEvent.LIFE_DAMAGE.equals(e.getType()));
                        n.merge("turns", 1L, Long::sum);
                        if (!damage) { n.merge("turns without damage", 1L, Long::sum); quiet[0]++; }
                        else { if (quiet[0] >= 3) n.merge("stalls (3+ turns without damage)", 1L, Long::sum); quiet[0] = 0; }
                        PlayerState p = room.getPlayer(prev);
                        @SuppressWarnings("unchecked") Set<String> held = (Set<String>) at[2];
                        if (!held.isEmpty()) {
                            n.merge("turns with a playable removal", 1L, Long::sum);
                            boolean unused = p != null && p.getHand().stream().anyMatch(c -> held.contains(c.getInstanceId()));
                            if (unused) {
                                n.merge("  ... and it stayed in the hand", 1L, Long::sum);
                                byDeck.merge("removal unused, " + deckOf.get(prev), 1L, Long::sum);
                                p.getHand().stream().filter(c -> held.contains(c.getInstanceId())).forEach(c -> removals.merge(c.getCardName(), 1L, Long::sum));
                            }
                        }
                        for (GameEvent e : turn) {
                            if (!GameEvent.NORMAL_SUMMON.equals(e.getType()) || !prev.equals(e.getPlayerId())) continue;
                            n.merge("normal summons", 1L, Long::sum);
                            CardSlot c = find(room, e.getCardInstanceId());
                            if (c != null && c.getRequirement() != null && !c.getRequirement().isBlank()) {
                                n.merge("  ... of a creature with a special summon", 1L, Long::sum);
                                @SuppressWarnings("unchecked") Set<String> special = (Set<String>) at[3];
                                if (special.contains(c.getInstanceId())) {
                                    n.merge("  ... whose special summon was possible at the turn's start", 1L, Long::sum);
                                    hardplays.merge(c.getCardName(), 1L, Long::sum);
                                }
                            }
                        }
                    }
                    String turnOf = room.getCurrentTurnUserId();
                    at[0] = turnOf;
                    at[1] = tl.size();
                    at[2] = turnOf == null ? Set.of() : playableRemovals(room, turnOf);
                    at[3] = turnOf == null ? Set.of() : new HashSet<>(com.drive.drive_manager.simulator.engine.SpecialRequirements.specialSummonable(room, turnOf));
                });
                return null;
            }));
        }
        for (Future<?> g : games) g.get();
        pool.shutdown();
        n.forEach((k, v) -> System.out.println("[diag] " + k + ": " + v));
        byDeck.forEach((k, v) -> System.out.println("[diag] " + k + ": " + v));
        removals.forEach((k, v) -> System.out.println("[diag-removal] " + k + ": " + v));
        hardplays.forEach((k, v) -> System.out.println("[diag-hard] " + k + ": " + v));
    }

    /** The cards in the hand it may play now whose effects destroy an enemy, when the rival has creatures on the field. */
    private static Set<String> playableRemovals(GameRoom room, String me) {
        PlayerState p = room.getPlayer(me), rival = room.getPlayer(room.getOpponentId(me));
        if (p == null || rival == null || rival.getField().stream().noneMatch(c -> "creature".equals(c.getCardType()))) return Set.of();
        Map<String, List<String>> legal = LegalActions.of(room, me);
        Set<String> out = new HashSet<>();
        for (CardSlot c : p.getHand()) {
            List<String> can = legal.getOrDefault(c.getInstanceId(), List.of());
            if (can.isEmpty()) continue;
            for (EffectScript e : CardEffects.of(c)) {
                String t = e.text() == null ? "" : e.text().toLowerCase(Locale.ROOT);
                // It destroys an enemy ("destruye…", "destrúyelo"), not "si algún aliado es destruido por el oponente"
                boolean destroys = t.contains("destruye") || t.contains("destrúye") || t.contains("destruir");
                if (destroys && (t.contains("enemigo") || t.contains("oponente") || t.contains("rival")) && !t.contains("es destruido")) { out.add(c.getInstanceId()); break; }
            }
        }
        return out;
    }

    private static CardSlot find(GameRoom room, String id) {
        if (id == null) return null;
        for (PlayerState p : room.getPlayers().values())
            for (List<CardSlot> z : List.of(p.getField(), p.getHand(), p.getTributeZone(), p.getDiscardPile(), p.getDeck(), p.getLifeStack()))
                for (CardSlot c : z) if (id.equals(c.getInstanceId())) return c;
        return null;
    }
}
