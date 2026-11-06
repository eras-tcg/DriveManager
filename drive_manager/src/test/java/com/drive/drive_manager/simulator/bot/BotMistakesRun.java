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
 * Mistakes the bot makes against itself ({@code bot.mistakes.settings}, every pair of starter decks,
 * {@code bot.mistakes.seeds} deals), read from the games' timelines: attacks that were blocked and lost the attacker
 * while the blocker lived (and by whom and what), attacks that traded, and utilities whose resolution did nothing, with
 * how many creatures of its own were on the field when it was played (Alimento De Las Sombras needs one). Only reads:
 * the bot is not changed. Run with {@code -Dbot.mistakes=true -Dtest=BotMistakesRun}.
 */
class BotMistakesRun {

    private static final Set<String> FRAME = Set.of(GameEvent.EFFECT_ACTIVATED, GameEvent.EFFECT_RESOLVED, GameEvent.TARGET, GameEvent.PLAY);

    @Test
    void mistakes() throws Exception {
        assumeTrue(Boolean.getBoolean("bot.mistakes"), "runs only with -Dbot.mistakes=true");
        BotSettings s = BotSettings.DEFAULT.with(System.getProperty("bot.mistakes.settings", ""));
        int seeds = Integer.getInteger("bot.mistakes.seeds", 1);
        ExecutorService pool = Executors.newFixedThreadPool(Integer.getInteger("bot.mistakes.threads", 8));
        Map<String, Long> n = new ConcurrentSkipListMap<>(), lost = new ConcurrentSkipListMap<>(), duds = new ConcurrentSkipListMap<>();
        List<String> cases = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> games = new ArrayList<>();
        for (int k = 1; k <= seeds; k++) for (String x : BotMatch.DECKS) for (String y : BotMatch.DECKS) {
            long seed = 11000L + k * 100 + BotMatch.DECKS.indexOf(x) * 10 + BotMatch.DECKS.indexOf(y);
            games.add(pool.submit(() -> {
                GameRoom[] last = {null};
                Map<String, Integer> alliesAtPlay = new HashMap<>();
                int[] seen = {0};
                // A winning blow at the start of a turn: the rival with 1 life and no active blocker, an attack legal
                Object[] lethal = {null, 0, null};
                BotMatch.playSeededWatching(x, y, new LookaheadBot(s), new LookaheadBot(s), seed, room -> {
                    last[0] = room;
                    if (lethal[0] != null) {
                        String p = (String) lethal[0];
                        n.merge("turns with a winning blow at the start", 1L, Long::sum);
                        boolean won = room.getStatus() != GameRoom.RoomStatus.IN_PROGRESS && p.equals(room.getWinnerId());
                        if (!won) {
                            n.merge("  ... and it did not win that turn", 1L, Long::sum);
                            List<GameEvent> turn = room.getTimeline().subList(Math.min((int) lethal[1], room.getTimeline().size()), room.getTimeline().size());
                            StringBuilder did = new StringBuilder();
                            for (GameEvent e : turn) if (p.equals(e.getPlayerId()) && List.of(GameEvent.NORMAL_SUMMON, GameEvent.SPECIAL_SUMMON, GameEvent.PLAY, GameEvent.ATTACK_DECLARED, GameEvent.EFFECT_ACTIVATED).contains(e.getType()))
                                did.append(e.getType()).append(' ').append(e.getCardName()).append("; ");
                            if (cases.size() < 60) cases.add("GOLPE PERDIDO " + x + "-" + y + " | " + lethal[2] + " | hizo: " + did);
                        }
                    }
                    lethal[0] = null;
                    String turnOf = room.getCurrentTurnUserId();
                    if (turnOf != null && room.getStatus() == GameRoom.RoomStatus.IN_PROGRESS) {
                        PlayerState rival = room.getPlayer(room.getOpponentId(turnOf)), me = room.getPlayer(turnOf);
                        boolean noBlocker = rival != null && rival.getField().stream().noneMatch(c -> "creature".equals(c.getCardType()) && com.drive.drive_manager.simulator.engine.EffectEngine.inFrontLine(c) && !c.isTapped());
                        List<String> attackers = new ArrayList<>();
                        for (Map.Entry<String, List<String>> l : com.drive.drive_manager.simulator.engine.LegalActions.of(room, turnOf).entrySet())
                            if (l.getValue().contains("attack")) me.getField().stream().filter(c -> c.getInstanceId().equals(l.getKey())).findFirst().ifPresent(c -> attackers.add(c.getCardName()));
                        // The front line's creatures that could attack once the turn's start is over (legal actions are
                        // only listed in the main phase): the active ones in the Línea frontal
                        List<String> front = me.getField().stream().filter(c -> "creature".equals(c.getCardType()) && com.drive.drive_manager.simulator.engine.EffectEngine.inFrontLine(c)).map(CardSlot::getCardName).toList();
                        if (rival != null && rival.getLifeStack().size() == 1 && noBlocker && (!attackers.isEmpty() || !front.isEmpty())) {
                            lethal[0] = turnOf;
                            lethal[1] = room.getTimeline().size();
                            lethal[2] = "gemas " + com.drive.drive_manager.simulator.engine.EffectEngine.gemsOf(room, turnOf) + ", fase " + room.getCurrentPhase()
                                    + ", atacantes legales " + attackers + ", en Línea frontal " + front + ", mano " + me.getHand().stream().map(CardSlot::getCardName).toList();
                        }
                    }
                    // Creatures of its own on the field when each utility is played (counted at the turn changes: the
                    // field at the start of the turn of the play, close enough for "had it any creature at all")
                    for (int i = seen[0]; i < room.getTimeline().size(); i++) {
                        GameEvent e = room.getTimeline().get(i);
                        if (GameEvent.PLAY.equals(e.getType()) && e.getResolutionId() != null) alliesAtPlay.putIfAbsent(e.getResolutionId(), -1);
                    }
                    seen[0] = room.getTimeline().size();
                });
                if (last[0] == null) return null;
                analyse(last[0], x + "-" + y, n, lost, duds, cases);
                return null;
            }));
        }
        for (Future<?> g : games) g.get();
        pool.shutdown();
        n.forEach((k, v) -> System.out.println("[mis] " + k + ": " + v));
        lost.forEach((k, v) -> System.out.println("[mis-lost] " + k + ": " + v));
        duds.forEach((k, v) -> System.out.println("[mis-dud] " + k + ": " + v));
        cases.stream().limit(30).forEach(c -> System.out.println("[mis-case] " + c));
    }

    /** One game's timeline: each attack from its declaration to its end, and each utility's resolution. */
    private static void analyse(GameRoom room, String decks, Map<String, Long> n, Map<String, Long> lost, Map<String, Long> duds, List<String> cases) {
        List<GameEvent> tl = room.getTimeline();
        // Own creatures on the field, kept event by event from summons and removals (enough to tell "none")
        Map<String, Set<String>> field = new HashMap<>();
        Map<String, String> typeOf = new HashMap<>();
        for (PlayerState p : room.getPlayers().values())
            for (List<CardSlot> z : List.of(p.getField(), p.getHand(), p.getTributeZone(), p.getDiscardPile(), p.getDeck(), p.getLifeStack()))
                for (CardSlot c : z) typeOf.put(c.getInstanceId(), c.getCardType());
        for (int i = 0; i < tl.size(); i++) {
            GameEvent e = tl.get(i);
            String type = e.getType();
            if (e.getCardInstanceId() != null && "creature".equals(typeOf.get(e.getCardInstanceId()))) {
                Set<String> mine = field.computeIfAbsent(String.valueOf(e.getPlayerId()), k -> new HashSet<>());
                if ("field".equals(e.getToZone()) || GameEvent.NORMAL_SUMMON.equals(type) || GameEvent.SPECIAL_SUMMON.equals(type) || GameEvent.EFFECT_SUMMON.equals(type)) mine.add(e.getCardInstanceId());
                if ("field".equals(e.getFromZone()) && e.getToZone() != null && !"field".equals(e.getToZone())) field.values().forEach(f -> f.remove(e.getCardInstanceId()));
                if (GameEvent.DESTROY.equals(type) || GameEvent.SACRIFICE.equals(type)) field.values().forEach(f -> f.remove(e.getCardInstanceId()));
            }
            if (GameEvent.ATTACK_DECLARED.equals(type)) {
                String attacker = e.getCardInstanceId(), who = e.getPlayerId();
                String blocker = null;
                Set<String> destroyed = new HashSet<>();
                int j = i + 1;
                for (; j < tl.size() && !GameEvent.ATTACK_ENDED.equals(tl.get(j).getType()) && !GameEvent.ATTACK_DECLARED.equals(tl.get(j).getType()); j++) {
                    GameEvent f = tl.get(j);
                    if (GameEvent.BLOCK.equals(f.getType())) blocker = f.getCardInstanceId();
                    if (GameEvent.DESTROY.equals(f.getType())) destroyed.add(f.getCardInstanceId());
                }
                n.merge("attacks", 1L, Long::sum);
                if (blocker == null) continue;
                n.merge("  blocked", 1L, Long::sum);
                boolean aDied = destroyed.contains(attacker), bDied = destroyed.contains(blocker);
                if (aDied && !bDied) {
                    n.merge("  ... attacker destroyed, blocker lived", 1L, Long::sum);
                    String name = nameOf(room, attacker);
                    lost.merge(name, 1L, Long::sum);
                    if (cases.size() < 60) cases.add(decks + " | " + who + " ataca con " + name + " (" + strengthAt(tl, i) + "), bloquea " + nameOf(room, blocker));
                } else if (aDied) n.merge("  ... both destroyed (trade)", 1L, Long::sum);
                else if (bDied) n.merge("  ... blocker destroyed", 1L, Long::sum);
                else n.merge("  ... nobody destroyed", 1L, Long::sum);
            }
            if (GameEvent.PLAY.equals(type) && "utility".equals(typeOf.get(e.getCardInstanceId())) && e.getResolutionId() != null) {
                String res = e.getResolutionId(), card = e.getCardInstanceId(), who = e.getPlayerId();
                boolean nothing = true, ended = false;
                for (int j = i + 1; j < tl.size(); j++) {
                    GameEvent f = tl.get(j);
                    if (!res.equals(f.getResolutionId())) continue;
                    if (GameEvent.EFFECT_RESOLVED.equals(f.getType())) { ended = true; break; }
                    if (!FRAME.contains(f.getType()) && !(card.equals(f.getCardInstanceId()) && "discard".equalsIgnoreCase(String.valueOf(f.getToZone())))) nothing = false;
                }
                if (!ended) continue;
                String name = nameOf(room, card);
                int allies = field.getOrDefault(String.valueOf(who), Set.of()).size();
                n.merge("utilities played", 1L, Long::sum);
                if (nothing) {
                    n.merge("  ... that did nothing", 1L, Long::sum);
                    duds.merge(name + (allies == 0 ? " (sin criaturas propias en el campo)" : "") + (e.getData() != null && Integer.valueOf(0).equals(e.getData().get("cost")) ? " [gratis]" : ""), 1L, Long::sum);
                }
            }
        }
    }

    private static int strengthAt(List<GameEvent> tl, int i) {
        Object s = tl.get(i).getData() == null ? null : tl.get(i).getData().get("strength");
        return s instanceof Number num ? num.intValue() : -1;
    }

    private static String nameOf(GameRoom room, String id) {
        for (PlayerState p : room.getPlayers().values())
            for (List<CardSlot> z : List.of(p.getField(), p.getHand(), p.getTributeZone(), p.getDiscardPile(), p.getDeck(), p.getLifeStack()))
                for (CardSlot c : z) if (id != null && id.equals(c.getInstanceId())) return c.getCardName();
        return String.valueOf(id);
    }
}
