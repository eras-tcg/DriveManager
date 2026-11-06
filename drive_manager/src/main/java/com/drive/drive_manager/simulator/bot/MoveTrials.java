package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.GameEvent;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;
import com.drive.drive_manager.simulator.room.PlayerState;

import com.drive.drive_manager.simulator.engine.EffectEngine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.ToDoubleBiFunction;

/**
 * One decision of the look-ahead bot: its tries of the candidate moves, until the best is found or time runs out. A
 * try plays the move on a copy of the room and then both sides by the heuristic until the turn passes
 * ({@link BotSettings#turns} times), and scores the table left. How the tries are shared out and dealt is in
 * the settings: {@code samples} for each move, or by successive halving; each on a deal of its own, or the k-th try of
 * every move on the same k-th deal.
 */
final class MoveTrials {

    /** Moves played at most on a copy per turn looked at (a turn rarely takes more than a few dozen). */
    static final int MAX_STEPS = 300;

    private final BotSettings settings;
    private final Sandbox sandbox;
    private final ToDoubleBiFunction<GameRoom, String> score;
    private final long deadline;
    /** The seeds of this decision's deals, drawn as they are needed (only with sameDeals). */
    private final List<Long> deals = new ArrayList<>();
    boolean ranOutOfTime;
    int movesTried;
    /**
     * Audit (tests only): when set for the thread, each decision by successive halving writes here every candidate with
     * its mean score, its tries and the round it was dropped in (0: it won), the chosen one first.
     */
    static final ThreadLocal<List<String>> AUDIT = new ThreadLocal<>();

    /** The mean score of the tries of the move chosen: what the bot expects the table to be worth. */
    double expected;

    MoveTrials(BotSettings settings, Sandbox sandbox, ToDoubleBiFunction<GameRoom, String> score, long deadline) {
        this.settings = settings;
        this.sandbox = sandbox;
        this.score = score;
        this.deadline = deadline;
    }

    /** The candidate with the best score (the first one, the heuristic's, on a tie or when time runs out). */
    GameAction best(GameRoom room, String me, List<GameAction> candidates) {
        return settings.halving() ? halving(room, me, candidates) : everyMove(room, me, candidates);
    }

    /** Each move tried {@code samples} times, in order, until time runs out. */
    private GameAction everyMove(GameRoom room, String me, List<GameAction> candidates) {
        int n = candidates.size();
        double[] sum = new double[n];
        int[] tries = new int[n];
        for (int i = 0; i < n; i++) {
            if (i > 0 && System.nanoTime() > deadline) {
                ranOutOfTime = true;
                break;
            }
            for (int k = 0; k < settings.samples(); k++) sum[i] += tryMove(room, me, candidates.get(i), deal(k), true);
            tries[i] = settings.samples();
            movesTried++;
        }
        return pick(candidates, sum, tries, bestMean(sum, tries));
    }

    /**
     * Successive halving: every move gets a first try, then the better half (by mean score) go on to more tries, round
     * after round, until one is left. Each round spends about the same, so the finalists get most of the tries; in all
     * it spends what {@code samples} tries of each move would. Each new deal is played by every move still in, so when
     * time runs out they have had (almost) the same tries; then the best mean so far wins.
     */
    private GameAction halving(GameRoom room, String me, List<GameAction> candidates) {
        int n = candidates.size();
        int rounds = Math.max(1, 32 - Integer.numberOfLeadingZeros(n - 1));
        int perRound = Math.max(1, settings.samples() * n / rounds);
        double[] sum = new double[n];
        int[] tries = new int[n];
        List<Integer> alive = new ArrayList<>();
        for (int i = 0; i < n; i++) alive.add(i);
        int[] droppedIn = new int[n];
        int round = 0;
        int dealt = 0;
        while (alive.size() > 1) {
            // firstTries: at least this many tries for every move in the first round (one bad deal does not drop a good move)
            int more = Math.max(dealt == 0 ? Math.max(1, (int) settings.get("firstTries")) : 1, perRound / alive.size());
            // innerFinal: the rest of its own turn thought (innerCands) only once this few moves are left: the cheap
            // tries sort the field, the deep ones decide among the finalists (each finalist gets the same tries)
            boolean deep = settings.get("innerFinal") < 1 || alive.size() <= settings.get("innerFinal");
            for (int k = dealt; k < dealt + more; k++) {
                for (int i : alive) {
                    if ((k > 0 || i > 0) && System.nanoTime() > deadline) {
                        ranOutOfTime = true;
                        return pick(candidates, sum, tries, bestMean(sum, tries));
                    }
                    sum[i] += tryMove(room, me, candidates.get(i), deal(k), deep);
                    tries[i]++;
                    if (k == 0) movesTried++;
                }
            }
            dealt += more;
            round++;
            // Stable: on equal means the earlier move (the heuristic's first) stays ahead
            alive.sort(Comparator.comparingDouble(i -> -sum[i] / tries[i]));
            for (int i : alive.subList((alive.size() + 1) / 2, alive.size())) droppedIn[i] = round;
            alive = new ArrayList<>(alive.subList(0, (alive.size() + 1) / 2));
        }
        if (AUDIT.get() != null) audit(room, candidates, sum, tries, droppedIn, alive.get(0));
        return pick(candidates, sum, tries, alive.get(0));
    }

    private static void audit(GameRoom room, List<GameAction> candidates, double[] sum, int[] tries, int[] droppedIn, int chosen) {
        List<String> out = AUDIT.get();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) order.add(i);
        order.sort(Comparator.comparingInt((Integer i) -> i == chosen ? -1 : droppedIn[i] == 0 ? 0 : -droppedIn[i] + 1000));
        for (int i : order) {
            out.add(String.format(java.util.Locale.ROOT, "%s%-60s media %8.1f  pruebas %2d  %s", i == chosen ? "ELEGIDA " : "        ",
                    describe(room, candidates.get(i)), tries[i] == 0 ? Double.NaN : sum[i] / tries[i], tries[i],
                    i == chosen ? "" : droppedIn[i] == 0 ? "finalista" : "cayó en la ronda " + droppedIn[i]));
        }
    }

    /** A candidate in words: its type and its card's name (and target's); a sequence, its moves. */
    static String describe(GameRoom room, GameAction a) {
        if (SequenceSearch.SEQUENCE.equals(a.getType())) {
            return "cadena: " + String.join(" → ", SequenceSearch.moves(a).stream().map(m -> describe(room, m)).toList());
        }
        String id = a.getString("instanceId"), target = a.getString("targetInstanceId");
        return a.getType() + (id == null ? "" : " " + name(room, id)) + (target == null ? "" : " → " + name(room, target));
    }

    private static String name(GameRoom room, String id) {
        for (PlayerState p : room.getPlayers().values())
            for (List<CardSlot> z : List.of(p.getHand(), p.getField(), p.getTributeZone(), p.getLifeStack(), p.getDiscardPile()))
                for (CardSlot c : z) if (id.equals(c.getInstanceId())) return c.getCardName();
        return id;
    }

    /**
     * The candidates the tries go to: the first maxCandidates as listed; with rankCands, when there are more, the
     * heuristic's move first and then the best by the table right after each move (its prompts answered by the
     * heuristic), so a good move listed late (an attack on a given enemy) is not cut.
     */
    static List<GameAction> top(Sandbox sandbox, GameRoom room, String me, List<GameAction> candidates, BotSettings settings) {
        int max = settings.maxCandidates();
        if (candidates.size() <= max || settings.get("rankCands") < 0.5) return candidates.subList(0, Math.min(candidates.size(), max));
        Map<GameAction, Double> quick = new java.util.IdentityHashMap<>();
        long seed = sandbox.deal();
        // At most a fifth of the decision's time: the moves not screened by then keep their place after the screened ones
        long until = System.nanoTime() + settings.budgetMs() * 200_000L;
        for (GameAction a : candidates.subList(1, candidates.size())) {
            if (System.nanoTime() > until) break;
            GameRoom copy = sandbox.fork(room, me, new Random(seed));
            try {
                Map<String, BotPlayer> sides = Map.of(me, new HeuristicBot(settings), copy.getOpponentId(me), new HeuristicBot(settings));
                double v = 0;
                boolean soft = SequenceSearch.soft(a);
                for (GameAction m : SequenceSearch.moves(a)) {
                    if (soft && (copy.getStatus() != GameRoom.RoomStatus.IN_PROGRESS || !me.equals(copy.getCurrentTurnUserId()))) break;
                    GameStateUpdate u = sandbox.act(copy, me, m);
                    if (u != null && u.getError() != null) { if (soft) continue; v = Double.NEGATIVE_INFINITY; break; }
                    SequenceSearch.settle(sandbox, copy, me, sides);
                }
                quick.put(a, v == Double.NEGATIVE_INFINITY ? v : TableScore.score(copy, me, settings));
            } finally {
                sandbox.drop(copy);
            }
        }
        List<GameAction> rest = new ArrayList<>(candidates.subList(1, candidates.size()));
        // Stable: the screened ones by their table, the rest (unscreened, -1e9) after them in the list's order
        rest.sort(Comparator.comparingDouble((GameAction a) -> -quick.getOrDefault(a, -1e9)));
        List<GameAction> out = new ArrayList<>(List.of(candidates.get(0)));
        out.addAll(rest.subList(0, max - 1));
        return out;
    }

    /** The move to play, with what the bot expects from it. */
    private GameAction pick(List<GameAction> candidates, double[] sum, int[] tries, int best) {
        expected = sum[best] / tries[best];
        return candidates.get(best);
    }

    /** The move with the best mean score among those tried (the earliest on a tie). */
    private static int bestMean(double[] sum, int[] tries) {
        int best = 0;
        for (int i = 1; i < sum.length; i++) if (tries[i] > 0 && sum[i] / tries[i] > sum[best] / tries[best]) best = i;
        return best;
    }

    /** The k-th deal of this decision when every move is tried on the same deals; else null (the sandbox's own chance). */
    private Random deal(int k) {
        if (!settings.sameDeals()) return null;
        while (deals.size() <= k) deals.add(sandbox.deal());
        return new Random(deals.get(k));
    }

    /** The small look-ahead that plays the rest of the bot's own turn in a try (innerCands, innerMs). */
    private BotPlayer inner(Random deal) {
        LookaheadBot bot = new LookaheadBot(settings.with("turns=1,samples=1,halving=0,seqDepth=0,innerCands=0,maxCandidates="
                + (int) settings.get("innerCands") + ",budgetMs=" + (int) settings.get("innerMs")));
        if (deal != null) bot.seed(deal.nextLong());
        return bot;
    }

    /** One try of the move on the deal of {@code seed} (the sequence search screens by it with seqRollout). */
    double once(GameRoom room, String me, GameAction move, long seed) {
        return tryMove(room, me, move, new Random(seed), false);
    }

    /** Plays the move on a copy and then both sides by the heuristic until the turn passes; the score of the table left. */
    private double tryMove(GameRoom room, String me, GameAction move, Random deal, boolean deep) {
        GameRoom copy = sandbox.fork(room, me, deal);
        // searchHope: in this try its searches of the deck's top find the best valid cards when they find any
        if (settings.get("searchHope") >= 0.5) copy.setSearchHope(me);
        int start = copy.getTimeline().size();
        try {
            String turn = copy.getCurrentTurnUserId();
            int passed = 0;
            // Both sides of the copy play by the bot's own rules
            Map<String, BotPlayer> sides = Map.of(me, new HeuristicBot(settings), copy.getOpponentId(me), new HeuristicBot(settings));
            // innerCands: the rest of its own turn decided by a small look-ahead (a few candidates, one try each, to the
            // end of the turn) instead of the heuristic; the rival's turn too with innerRival
            BotPlayer thinker = !deep || settings.get("innerCands") < 1 ? null : inner(deal);
            // innerRival (the user's call, 2026-11-05): the rival's own turn in the try decided by the same small look-ahead
            // as its own (innerCands, in the finalists' tries): the rival it expects is no longer the plain heuristic
            BotPlayer rivalThinker = thinker == null || settings.get("innerRival") < 0.5 ? null : inner(deal);
            // A sequence's moves one after the other, what each opens answered before the next
            List<GameAction> moves = SequenceSearch.moves(move);
            // The fewest gems it had during its own turn: below 0 they went to the rival for their turn (gemDebt)
            int fewest = Integer.MAX_VALUE;
            // overExtendRival: the gems it pays in the rival's turn (an effect's summon, a life played) are the rival's at
            // once, in the turn they play (the Contador is shared): they count as gems below 0 too
            int paidInTheirs = 0;
            int rivalHand = copy.getPlayer(copy.getOpponentId(me)) == null ? 0 : copy.getPlayer(copy.getOpponentId(me)).getHand().size();
            // A cascade (soft) skips the attacks no longer possible and stops when the game or its turn is over
            boolean soft = SequenceSearch.soft(move);
            for (int i = 0; i < moves.size(); i++) {
                if (i > 0) SequenceSearch.settle(sandbox, copy, me, sides);
                if (soft && i > 0 && (copy.getStatus() != GameRoom.RoomStatus.IN_PROGRESS || !me.equals(copy.getCurrentTurnUserId()))) break;
                int before = EffectEngine.gemsOf(copy, me);
                boolean theirs = !me.equals(copy.getCurrentTurnUserId());
                GameStateUpdate first = sandbox.act(copy, me, moves.get(i));
                if (first != null && first.getError() != null) { if (soft && i > 0) continue; return Double.NEGATIVE_INFINITY; }
                if (theirs) paidInTheirs += Math.max(0, before - EffectEngine.gemsOf(copy, me));
                if (me.equals(copy.getCurrentTurnUserId())) fewest = Math.min(fewest, EffectEngine.gemsOf(copy, me));
            }
            // frontRisk: the exposure to the rival's removal is taken when its turn begins (RemovalRisk), not at the end
            // of the try: by then its own turn has begun and everything moved up again
            Map<String, Double> atRisk = null;
            for (int step = 0; step < MAX_STEPS * settings.turns() && copy.getStatus() == GameRoom.RoomStatus.IN_PROGRESS; step++) {
                if (!turn.equals(copy.getCurrentTurnUserId())) {
                    if (me.equals(turn) && (atRisk == null || atRisk.isEmpty())) atRisk = RemovalRisk.risks(copy, copy.getPlayer(me), copy.getPlayer(copy.getOpponentId(me)), settings);
                    turn = copy.getCurrentTurnUserId();
                    if (++passed >= settings.turns()) break;
                }
                String seat = copy.getPrompt() != null ? copy.getPrompt().getPlayerId() : copy.getCurrentTurnUserId();
                BotPlayer side = thinker != null && passed == 0 && me.equals(seat) ? thinker
                        : rivalThinker != null && passed == 1 && !me.equals(seat) && seat.equals(copy.getCurrentTurnUserId()) ? rivalThinker : sides.get(seat);
                GameAction next = side == null ? null : side.next(copy, seat);
                if (next == null) break;
                int before = EffectEngine.gemsOf(copy, me);
                boolean theirs = me.equals(seat) && !me.equals(copy.getCurrentTurnUserId());
                GameStateUpdate update = sandbox.act(copy, seat, next);
                if (theirs) paidInTheirs += Math.max(0, before - EffectEngine.gemsOf(copy, me));
                if (update != null && update.getError() != null) side.refused(next, update.getError());
                else side.played(copy, next);
                if (me.equals(copy.getCurrentTurnUserId())) fewest = Math.min(fewest, EffectEngine.gemsOf(copy, me));
            }
            // winFlat (duel st1-st2 s13395, 2026-11-06): a game won or lost in the try is worth that alone, without what the
            // try added (structures used, the plan's bonus): else winning later, after more moves (a deck emptied, three
            // Volcanes used), outscored the win at hand
            if (settings.get("winFlat") >= 0.5 && copy.getStatus() != GameRoom.RoomStatus.IN_PROGRESS && copy.getWinnerId() != null) return score.applyAsDouble(copy, me);
            double debt = (fewest == Integer.MAX_VALUE ? 0 : Math.max(0, -fewest)) + (settings.get("overExtendRival") >= 0.5 ? paidInTheirs : 0);
            // rollTriggers: per effect of its own activated in the rest of its turn (searches, draws, summons by effect:
            // what a combo earns and the table at the end of the try barely shows)
            double fired = 0;
            if (settings.get("rollTriggers") != 0) {
                for (int i = start; i < copy.getTimeline().size(); i++) {
                    var e = copy.getTimeline().get(i);
                    if (GameEvent.TURN_END.equals(e.getType())) break;
                    if (GameEvent.EFFECT_ACTIVATED.equals(e.getType()) && me.equals(e.getPlayerId())) fired++;
                }
            }
            double lives = settings.get("lifeExp") >= 0.5 ? LifeHits.correction(copy, me, start, settings) : 0;
            // sUse: what its structures yielded in the try (StructureUse)
            double used = settings.get("sUse") != 0 ? settings.get("sUse") * StructureUse.count(copy, me, start) : 0;
            double leaf = LeafPlay.bonus(sandbox, copy, me, settings, score, deal);
            // planKeep: the rest of the plan it chose earlier is worth its bonus more (another plan must beat it by that)
            double kept = move.getPayload().get("bonus") instanceof Number b ? b.doubleValue() : 0;
            double exposed = RemovalRisk.charge(atRisk, copy.getPlayer(me), settings);
            return score.applyAsDouble(copy, me) - TableScore.overExtend(debt, rivalHand, settings) + settings.get("rollTriggers") * fired + lives + leaf + kept + used - exposed;
        } finally {
            sandbox.drop(copy);
        }
    }
}
