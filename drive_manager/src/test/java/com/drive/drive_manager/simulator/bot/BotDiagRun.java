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

    /** The first attacks that lost the attacker for nothing all turn, each with its events (to read why). */
    private static final List<String> CASES = Collections.synchronizedList(new ArrayList<>());

    @Test
    void diag() throws Exception {
        assumeTrue(Boolean.getBoolean("bot.diag"), "runs only with -Dbot.diag=true");
        BotSettings s = BotSettings.DEFAULT.with(System.getProperty("bot.diag.settings", ""));
        int seeds = Integer.getInteger("bot.diag.seeds", 1);
        ExecutorService pool = Executors.newFixedThreadPool(Integer.getInteger("bot.diag.threads", 8));
        Map<String, Long> n = new ConcurrentSkipListMap<>();
        Map<String, Long> byDeck = new ConcurrentSkipListMap<>();
        Map<String, Long> removals = new ConcurrentSkipListMap<>(), hardplays = new ConcurrentSkipListMap<>(), suicides = new ConcurrentSkipListMap<>();
        List<Future<?>> games = new ArrayList<>();
        // bot.diag.rival: the bot measured plays that other bot instead of itself, from both seats; only its own turns count
        String rivalSettings = System.getProperty("bot.diag.rival");
        BotSettings rs = rivalSettings == null ? null : BotSettings.DEFAULT.with(rivalSettings);
        for (int k = 1; k <= seeds; k++) for (String x : BotMatch.DECKS) for (String y : BotMatch.DECKS) for (String subject : rs == null ? List.of("") : List.of("host", "guest")) {
            long seed = 9000L + k * 100 + BotMatch.DECKS.indexOf(x) * 10 + BotMatch.DECKS.indexOf(y);
            games.add(pool.submit(() -> {
                Map<String, String> deckOf = Map.of("host", x, "guest", y);
                // At each turn's start: the turn's player, where the timeline was, the removals it could play
                Object[] at = {null, 0L, Set.<String>of(), Set.<String>of()};
                int[] quiet = {0};
                BotMatch.Result result = BotMatch.playSeededWatching(x, y, new LookaheadBot("guest".equals(subject) ? rs : s), new LookaheadBot("host".equals(subject) ? rs : s), seed, room -> {
                    List<GameEvent> tl = room.getTimeline();
                    String prev = (String) at[0];
                    if (prev != null && (subject.isEmpty() || subject.equals(prev))) {
                        long from = (long) at[1];
                        List<GameEvent> turn = tl.stream().filter(e -> e.getSeq() > from).toList();
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
                        attacks(turn, prev, n, suicides, room);
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
                    at[1] = tl.isEmpty() ? 0L : tl.get(tl.size() - 1).getSeq();
                    at[2] = turnOf == null ? Set.of() : playableRemovals(room, turnOf);
                    at[3] = turnOf == null ? Set.of() : new HashSet<>(com.drive.drive_manager.simulator.engine.SpecialRequirements.specialSummonable(room, turnOf));
                });
                n.merge("games", 1L, Long::sum);
                n.merge("games ended by: " + result.reason(), 1L, Long::sum);
                if (!subject.isEmpty()) n.merge(subject.equals(result.winner()) ? "games won by the bot measured" : "games not won by it", 1L, Long::sum);
                return null;
            }));
        }
        for (Future<?> g : games) g.get();
        pool.shutdown();
        n.forEach((k, v) -> System.out.println("[diag] " + k + ": " + v));
        byDeck.forEach((k, v) -> System.out.println("[diag] " + k + ": " + v));
        removals.forEach((k, v) -> System.out.println("[diag-removal] " + k + ": " + v));
        hardplays.forEach((k, v) -> System.out.println("[diag-hard] " + k + ": " + v));
        suicides.forEach((k, v) -> System.out.println("[diag-suicide] " + k + ": " + v));
        CASES.forEach(c -> System.out.println("[diag-case] " + c));
        System.out.println("[diag] attack floor: checks " + AttackFloor.CHECKS.get() + ", passed with no copy " + AttackFloor.SAFE.get() + ", copies " + AttackFloor.FORKS.get());
    }

    /**
     * The attacks of the turn's player: how many lost the attacker (destroyed during its attack), how many of those for
     * nothing then (no enemy destroyed, no life taken in that attack), how many with no effect of its own activated
     * either, and how many with nothing gained in the rest of the turn (no later enemy destroyed or life taken: not a
     * bait). An attack ends at its ATTACK_ENDED, at the next attack or with the turn: the engine records no ATTACK_ENDED
     * when the attacker is destroyed.
     */
    private static void attacks(List<GameEvent> turn, String me, Map<String, Long> n, Map<String, Long> suicides, GameRoom room) {
        // The attack in course: its attacker's id and name, and whether it died, gained something, activated an effect
        String attacker = null, name = null;
        boolean died = false, gained = false, fired = false;
        // The attacks of the turn that lost the attacker for nothing, still without a gain after them: {name, fired}
        List<Object[]> open = new ArrayList<>();
        StringBuilder events = new StringBuilder();
        for (int i = 0; i <= turn.size(); i++) {
            GameEvent e = i < turn.size() ? turn.get(i) : null;
            String type = e == null ? null : e.getType();
            Map<String, Object> data = e == null || e.getData() == null ? Map.of() : e.getData();
            boolean declared = e != null && GameEvent.ATTACK_DECLARED.equals(type) && me.equals(e.getPlayerId());
            boolean gain = e != null && ((GameEvent.DESTROY.equals(type) && data.get("ownerId") != null && !me.equals(String.valueOf(data.get("ownerId"))))
                    || (GameEvent.LIFE_DAMAGE.equals(type) && data.get("defenderId") != null && !me.equals(String.valueOf(data.get("defenderId")))));
            if (attacker != null && !declared && e != null) {
                if (!GameEvent.COMBAT_STEP.equals(type)) events.append(' ').append(type).append('/').append(e.getPlayerId()).append('/').append(e.getCardName())
                        .append(GameEvent.CLASH.equals(type) ? data.toString() : "");
                if (gain) gained = true;
                if (GameEvent.DESTROY.equals(type) && attacker.equals(e.getCardInstanceId())) died = true;
                if (GameEvent.EFFECT_ACTIVATED.equals(type) && me.equals(e.getPlayerId())) fired = true;
            }
            if (attacker != null && (e == null || declared || GameEvent.ATTACK_ENDED.equals(type))) {
                if (died) n.merge("  ... that lost the attacker", 1L, Long::sum);
                if (died && gained) n.merge("  ... in a trade (an enemy destroyed or a life taken in that attack)", 1L, Long::sum);
                if (died && !gained) {
                    n.merge("  ... for nothing in that attack (no enemy destroyed, no life taken)", 1L, Long::sum);
                    open.add(new Object[]{name, fired, events.toString()});
                }
                attacker = null;
            }
            // A gain after an attacker lost for nothing: it may have been a bait (it exhausted a blocker)
            if (gain && attacker == null) {
                n.merge("  ... lost for nothing, a gain later in the turn (bait?)", (long) open.size(), Long::sum);
                open.clear();
            }
            if (e == null) break;
            if (me.equals(e.getPlayerId()) && (GameEvent.SPECIAL_SUMMON.equals(type) || GameEvent.EFFECT_SUMMON.equals(type))) n.merge("summons, " + type, 1L, Long::sum);
            if (GameEvent.BLOCK.equals(type)) n.merge("blocks (of its attacks)", 1L, Long::sum);
            if (declared) {
                attacker = e.getCardInstanceId();
                name = e.getCardName();
                died = gained = fired = false;
                events.setLength(0);
                events.append("byEffect=").append(data.get("byEffect")).append(" target=").append(data.get("targetId") != null);
                n.merge("attacks", 1L, Long::sum);
            }
        }
        for (Object[] s : open) {
            n.merge("  ... lost for nothing, nothing gained in the rest of the turn (suicide)", 1L, Long::sum);
            if (!(boolean) s[1]) n.merge("  ... suicide with no effect of its own activated either", 1L, Long::sum);
            suicides.merge(String.valueOf(s[0]), 1L, Long::sum);
            PlayerState rival = room.getPlayer(room.getOpponentId(me));
            if (CASES.size() < 20) CASES.add(s[0] + " (rival's lives after the turn: " + (rival == null ? "?" : rival.getLifeStack().size()) + ") " + s[2]);
        }
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
