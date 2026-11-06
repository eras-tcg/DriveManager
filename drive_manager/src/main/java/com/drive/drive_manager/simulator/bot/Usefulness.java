package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.EffectEngine;
import com.drive.drive_manager.simulator.engine.GameEvent;
import com.drive.drive_manager.simulator.engine.Prompt;
import com.drive.drive_manager.simulator.room.ActionType;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;
import com.drive.drive_manager.simulator.room.PlayerState;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Does a move do anything? It is played on a copy of the room, what it opens answered by the heuristic until the
 * table is quiet, and the table compared with the one before: every card of both sides (where it is and how it
 * stands), the grants on the field and the bot's gems once the price is paid. Only the card played may move (from the
 * hand or the Tributos to the Descarte). A utility whose effect finds nothing to do (no creature to summon, no target
 * worth it) changes nothing else: the bot keeps it for when it does (hUseful).
 */
final class Usefulness {

    private static final ThreadLocal<Sandbox> SANDBOX = ThreadLocal.withInitial(Sandbox::new);
    /**
     * Verdicts already found, per thread: the same card in the same room with the same hand, field, Tributos and gems
     * gets the same answer (a simulation asks again at every step of a turn). Emptied when it grows large.
     */
    private static final ThreadLocal<Map<String, Boolean>> SEEN = ThreadLocal.withInitial(java.util.HashMap::new);

    /** How often it was asked and how often the answer was "nothing" (the bot runs report them). */
    static final java.util.concurrent.atomic.AtomicLong ASKED = new java.util.concurrent.atomic.AtomicLong(), NOTHING = new java.util.concurrent.atomic.AtomicLong();
    /** Debug: the first tables that differed, before and after (null: not kept). */
    static volatile java.util.List<String> diffs;
    /** Debug: per card asked about as a yes, how often it did something and how often nothing. */
    static final java.util.Map<String, int[]> BY_CARD = new java.util.concurrent.ConcurrentHashMap<>();

    private Usefulness() {}

    /**
     * hUsefulEnd, for the bot's own decisions: is the turn better with the move than without it? The rest of the turn
     * is played on two copies dealt alike by the heuristic, one with the move and one without the card (out of the
     * hand), and the table's score compared once the turn passes. What lasts only until the end of the turn and is not
     * used (a keyword to a creature that cannot attack) is gone by then, and a price that costs a later summon counts.
     */
    static boolean playPaysOff(GameRoom room, String me, GameAction play, BotSettings rules) {
        if (!ActionType.PLAY_CARD.is(play)) return true;
        String id = play.getString("instanceId");
        CardSlot card = room.getPlayer(me) == null ? null : room.getPlayer(me).getHand().stream()
                .filter(c -> c.getInstanceId().equals(id)).findFirst().orElse(null);
        if (card == null || !"utility".equals(card.getCardType())) return true;
        return paysOff(room, me, play, null, Set.of(id), rules);
    }

    /** The same for a yes to an optional effect of a utility of its own: against the no. */
    static boolean yesPaysOff(GameRoom room, String me, Prompt prompt, List<String> yes, BotSettings rules) {
        PlayerState self = room.getPlayer(me);
        if (self == null || yes.isEmpty()) return true;
        Set<String> moving = new java.util.HashSet<>(yes);
        String source = prompt.getSourceName() == null ? "" : BotSettings.cardKey(prompt.getSourceName());
        boolean utility = false;
        for (List<CardSlot> zone : List.of(self.getHand(), self.getTributeZone(), self.getField(), self.getLifeStack())) {
            for (CardSlot c : zone) {
                if (BotSettings.cardKey(c.getCardName()).equals(source)) moving.add(c.getInstanceId());
                if (moving.contains(c.getInstanceId()) && "utility".equals(c.getCardType())) utility = true;
            }
        }
        for (CardSlot c : prompt.getCards()) if (yes.contains(c.getInstanceId()) && "utility".equals(c.getCardType())) utility = true;
        if (!utility) return true;
        GameAction accept = BotPlayer.action(ActionType.RESOLVE_PROMPT, Map.of("promptId", prompt.getId(), "chosen", yes, "order", List.of()));
        GameAction decline = BotPlayer.action(ActionType.RESOLVE_PROMPT, Map.of("promptId", prompt.getId(), "chosen", List.of(), "order", List.of()));
        return paysOff(room, me, accept, decline, moving, rules);
    }

    private static boolean paysOff(GameRoom room, String me, GameAction move, GameAction without, Set<String> moving, BotSettings rules) {
        Map<String, Boolean> seen = SEEN.get();
        String key = "end|" + key(room, me, move);
        Boolean known = seen.get(key);
        if (known != null) return known;
        if (seen.size() > 4096) seen.clear();
        Double yes = endScore(room, me, move, moving, rules, false);
        Double no = endScore(room, me, without, moving, rules, true);
        boolean better = yes == null || no == null || yes > no + 1e-6;
        ASKED.incrementAndGet();
        if (!better) NOTHING.incrementAndGet();
        seen.put(key, better);
        return better;
    }

    /** The score once the turn passes, the move played (or none) and the rest of the turn by the heuristic; null: refused. */
    private static Double endScore(GameRoom room, String me, GameAction move, Set<String> moving, BotSettings rules, boolean without) {
        Sandbox sandbox = SANDBOX.get();
        GameRoom copy = sandbox.fork(room, me, new Random(1));
        try {
            BotSettings plain = rules.with("hUseful=0,hUsefulEnd=0");
            Map<String, BotPlayer> sides = Map.of(me, new HeuristicBot(plain), copy.getOpponentId(me), new HeuristicBot(plain));
            // Without the move, the card is not there to play later in the turn either
            if (without) for (PlayerState p : copy.getPlayers().values()) p.getHand().removeIf(c -> moving.contains(c.getInstanceId()));
            String turn = copy.getCurrentTurnUserId();
            if (move != null) {
                GameStateUpdate u = sandbox.act(copy, me, move);
                if (u != null && u.getError() != null) return null;
            }
            for (int step = 0; step < MoveTrials.MAX_STEPS && copy.getStatus() == GameRoom.RoomStatus.IN_PROGRESS && turn != null && turn.equals(copy.getCurrentTurnUserId()); step++) {
                String seat = copy.getPrompt() != null ? copy.getPrompt().getPlayerId() : copy.getCurrentTurnUserId();
                BotPlayer side = sides.get(seat);
                GameAction next = side == null ? null : side.next(copy, seat);
                if (next == null) break;
                GameStateUpdate u = sandbox.act(copy, seat, next);
                if (u != null && u.getError() != null) side.refused(next, u.getError());
                else side.played(copy, next);
            }
            return TableScore.score(copy, me, rules);
        } finally {
            sandbox.drop(copy);
        }
    }

    /** A play from the hand: PLAY_CARD of a utility. Other moves always do something. */
    static boolean playDoesSomething(GameRoom room, String me, GameAction play, BotSettings rules) {
        if (!ActionType.PLAY_CARD.is(play)) return true;
        String id = play.getString("instanceId");
        CardSlot card = room.getPlayer(me) == null ? null : room.getPlayer(me).getHand().stream()
                .filter(c -> c.getInstanceId().equals(id)).findFirst().orElse(null);
        if (card == null || !"utility".equals(card.getCardType())) return true;
        boolean r = doesSomething(room, me, play, null, Set.of(id), rules);
        int[] n = BY_CARD.computeIfAbsent("[jugar] " + card.getCardName(), k -> new int[2]);
        synchronized (n) { n[r ? 0 : 1]++; }
        return r;
    }

    /** A yes to an optional effect of its own ("puedes jugar esta carta sin pagar su coste"): the card it plays may move. */
    static boolean yesDoesSomething(GameRoom room, String me, Prompt prompt, List<String> yes, BotSettings rules) {
        String source = prompt.getSourceName() == null ? "" : BotSettings.cardKey(prompt.getSourceName());
        PlayerState self = room.getPlayer(me);
        if (self == null) return true;
        // The card the effect is of (asked as a yes: its name; as the effects to chain: the card chosen) may move
        Set<String> names = new java.util.HashSet<>(Set.of(source));
        for (CardSlot c : prompt.getCards()) if (yes.contains(c.getInstanceId())) names.add(BotSettings.cardKey(c.getCardName()));
        Set<String> moving = new java.util.HashSet<>(yes);
        for (List<CardSlot> zone : List.of(self.getHand(), self.getTributeZone(), self.getField())) {
            for (CardSlot c : zone) if (names.contains(BotSettings.cardKey(c.getCardName()))) moving.add(c.getInstanceId());
        }
        // Only utilities are judged: a structure or a creature stays and is worth having (its value is the table's)
        boolean utility = false;
        for (List<CardSlot> zone : List.of(self.getHand(), self.getTributeZone(), self.getField(), self.getLifeStack())) {
            for (CardSlot c : zone) if (moving.contains(c.getInstanceId()) && "utility".equals(c.getCardType())) utility = true;
        }
        for (CardSlot c : prompt.getCards()) if (yes.contains(c.getInstanceId()) && "utility".equals(c.getCardType())) utility = true;
        if (!utility) return true;
        GameAction accept = BotPlayer.action(ActionType.RESOLVE_PROMPT, Map.of("promptId", prompt.getId(), "chosen", yes, "order", List.of()));
        GameAction decline = BotPlayer.action(ActionType.RESOLVE_PROMPT, Map.of("promptId", prompt.getId(), "chosen", List.of(), "order", List.of()));
        boolean r = doesSomething(room, me, accept, decline, moving, rules);
        int[] n = BY_CARD.computeIfAbsent(names + " " + prompt.getPurpose() + " " + prompt.getKind(), k -> new int[2]);
        synchronized (n) { n[r ? 0 : 1]++; }
        return r;
    }

    /**
     * The table after the move against the table without it: with no move for a play from the hand (the turn is
     * quiet), else after the "no" (both copies dealt alike and played on alike, so only the effect differs).
     */
    private static boolean doesSomething(GameRoom room, String me, GameAction move, GameAction without, Set<String> moving, BotSettings rules) {
        Map<String, Boolean> seen = SEEN.get();
        String key = key(room, me, move);
        Boolean known = seen.get(key);
        if (known != null) return known;
        if (seen.size() > 4096) seen.clear();
        boolean found = judge(room, me, move, without, moving, rules);
        seen.put(key, found);
        return found;
    }

    /** The room (this copy), the move and what the bot holds: hand, field (and how it stands), Tributos, gems, phase. */
    private static String key(GameRoom room, String me, GameAction move) {
        PlayerState p = room.getPlayer(me);
        StringBuilder sb = new StringBuilder(room.getRoomId()).append('|').append(move.getType()).append(move.getPayload()).append('|')
                .append(EffectEngine.gemsOf(room, me)).append(room.getCurrentTurnUserId()).append(room.getChain().size());
        for (List<CardSlot> zone : List.of(p.getHand(), p.getField(), p.getTributeZone())) {
            sb.append('/');
            for (CardSlot c : zone) sb.append(c.getInstanceId()).append(c.isTapped() ? "t," : ",");
        }
        PlayerState other = room.getPlayer(room.getOpponentId(me));
        if (other != null) for (CardSlot c : other.getField()) sb.append(c.getInstanceId()).append(c.isTapped() ? "t," : ",");
        return sb.toString();
    }

    private static boolean judge(GameRoom room, String me, GameAction move, GameAction without, Set<String> moving, BotSettings rules) {
        String yes = outcome(room, me, move, moving, rules);
        if (yes == null) return true;
        String no = without == null ? outcome(room, me, null, moving, rules) : outcome(room, me, without, moving, rules);
        boolean something = !yes.equals(no);
        ASKED.incrementAndGet();
        if (!something) NOTHING.incrementAndGet();
        else if (diffs != null && diffs.size() < 8 && no != null && ActionType.RESOLVE_PROMPT.is(move)) diffs.add(move.getType() + " " + firstDiff(no, yes));
        return something;
    }

    /** The table once the move is played and what it opens answered (null: refused, or the game ended). */
    private static String outcome(GameRoom room, String me, GameAction move, Set<String> moving, BotSettings rules) {
        Sandbox sandbox = SANDBOX.get();
        GameRoom copy = sandbox.fork(room, me, new Random(1));
        try {
            int events = copy.getTimeline().size();
            if (move != null) {
                GameStateUpdate u = sandbox.act(copy, me, move);
                // Refused: the engine says so to the bot itself
                if (u != null && u.getError() != null) return null;
                // What the move opens, answered without this check (no copies within copies)
                BotSettings plain = rules.with("hUseful=0");
                SequenceSearch.settle(sandbox, copy, me, Map.of(me, new HeuristicBot(plain), copy.getOpponentId(me), new HeuristicBot(plain)));
            }
            if (copy.getStatus() != GameRoom.RoomStatus.IN_PROGRESS) return null;
            // The gems with what was paid to play cards given back: paying is not doing
            int paid = copy.getTimeline().subList(events, copy.getTimeline().size()).stream()
                    .filter(e -> GameEvent.PLAY.equals(e.getType()) && e.getData() != null && e.getData().get("cost") instanceof Number)
                    .mapToInt(e -> ((Number) e.getData().get("cost")).intValue()).sum();
            return table(copy, moving) + "|gems " + (EffectEngine.gemsOf(copy, me) + paid);
        } finally {
            sandbox.drop(copy);
        }
    }

    private static String firstDiff(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) i++;
        return "@" + i + " [" + a.substring(Math.max(0, i - 60), Math.min(a.length(), i + 120)) + "] vs [" + b.substring(Math.max(0, i - 60), Math.min(b.length(), i + 120)) + "]";
    }

    /** Every card of both sides but the ones that may move, zone by zone, and the grants on the field. */
    private static String table(GameRoom room, Set<String> moving) {
        StringBuilder sb = new StringBuilder();
        for (PlayerState p : room.getPlayers().values()) {
            sb.append(p.getUserId()).append('|');
            // Every card as it stands (keywords and marks given included), but not where it is drawn on the table nor
            // the order of a zone, but the deck's (what comes next)
            for (List<CardSlot> zone : List.of(p.getHand(), p.getField(), p.getTributeZone(), p.getDiscardPile(), p.getDeck(), p.getLifeStack())) {
                java.util.stream.Stream<String> cards = zone.stream().filter(c -> !moving.contains(c.getInstanceId())).map(Usefulness::card);
                sb.append(String.join(",", zone == p.getDeck() ? cards.toList() : cards.sorted().toList())).append('/');
            }
            // A card that may move but stays on the field (a utility kept in the Retaguardia) is worth something there
            for (CardSlot c : p.getField()) if (moving.contains(c.getInstanceId())) sb.append("kept ").append(c.getInstanceId());
        }
        return sb.append(room.getFieldGrants()).toString();
    }

    private static final java.util.regex.Pattern PLACE = java.util.regex.Pattern.compile("\\b[xy]=[^,)]*, ?");

    private static String card(CardSlot c) {
        return PLACE.matcher(c.toString()).replaceAll("") + ":" + EffectEngine.strength(c);
    }
}
