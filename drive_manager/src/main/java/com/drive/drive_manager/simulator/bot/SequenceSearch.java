package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.room.ActionType;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;
import com.drive.drive_manager.simulator.room.PlayerState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Sequences of the bot's own moves within its turn, so a combo is found by search and not left to the heuristic that
 * finishes the turn in the copies (its fixed order rarely plays a combo's second step). Off unless {@code seqDepth} is 2
 * or 3. Each sequence is applied on a copy of the room (the prompts its moves open answered by the heuristic) and
 * screened at once by the table's score, with no rollout, plus {@code seqTriggers} per effect of its own it activated
 * on the way (the engine's own record of what fired: no card is named). It grows one move at a time keeping the best
 * {@code seqKeep}; those join the candidates as one move each, and the rollouts play the whole sequence before the
 * heuristic takes over. The bot plays a sequence's first move and thinks again at its next decision. Screening stops at
 * {@code seqShare} of the decision's time budget (a quarter). With {@code seqRollout} each sequence is screened by one
 * try instead (the turn and the rival's played by the heuristic, as the candidates are judged), on the same deal.
 */
final class SequenceSearch {

    /** A candidate that is several moves in a row ("moves": the list). Never sent to the engine. */
    static final String SEQUENCE = "SEQUENCE";
    /** Moves played at most to answer what a sequence's move opens before the next one. */
    private static final int MAX_SETTLE = 200;

    private SequenceSearch() {}

    static GameAction of(List<GameAction> moves) {
        return BotPlayer.action(SEQUENCE, Map.of("moves", moves));
    }

    /**
     * cascade: every direct attack it can make, the strongest attacker first, as one candidate. Played in full in the
     * tries (blocks, answers and lives that were not removed included); an attack no longer possible is skipped and the
     * game won ends it. So the search sees a win, or good trades, that the heuristic finishing the turn never plays
     * (it does not attack into blockers). The bot makes the first attack and thinks again after it. Null with fewer
     * than 2 attackers.
     */
    static GameAction cascade(GameRoom room, String me) {
        PlayerState self = room.getPlayer(me);
        if (self == null) return null;
        Map<String, List<String>> legal = com.drive.drive_manager.simulator.engine.LegalActions.of(room, me);
        List<GameAction> attacks = self.getField().stream().filter(c -> legal.getOrDefault(c.getInstanceId(), List.of()).contains("attack"))
                .sorted(Comparator.comparingInt((CardSlot c) -> com.drive.drive_manager.simulator.engine.EffectEngine.strength(c)).reversed())
                .map(c -> BotPlayer.action(ActionType.ATTACK, Map.of("instanceId", c.getInstanceId()))).toList();
        return attacks.size() < 2 ? null : BotPlayer.action(SEQUENCE, Map.of("moves", attacks, "soft", true));
    }

    /** A sequence whose later moves are skipped when no longer possible (the cascade), not one that fails with them. */
    static boolean soft(GameAction a) {
        return a != null && SEQUENCE.equals(a.getType()) && Boolean.TRUE.equals(a.getPayload().get("soft"));
    }

    @SuppressWarnings("unchecked")
    static List<GameAction> moves(GameAction a) {
        return a != null && SEQUENCE.equals(a.getType()) ? (List<GameAction>) a.getPayload().get("moves") : List.of(a);
    }

    /** The move to play now: a sequence's first, or the move itself. */
    static GameAction first(GameAction a) {
        return a == null ? null : moves(a).get(0);
    }

    private record Screened(List<GameAction> moves, double score, List<GameAction> next) {}

    /** The best sequences of 2 or more moves starting with one of {@code firsts}, as candidates (empty when off). */
    static List<GameAction> best(Sandbox sandbox, GameRoom room, String me, List<GameAction> firsts, BotSettings s) {
        int depth = (int) s.get("seqDepth"), keep = Math.max(1, (int) s.get("seqKeep"));
        GameAction cascade = s.get("cascade") >= 0.5 ? cascade(room, me) : null;
        // lethalLives: a win found by the engine first (LethalSearch), ahead of every sequence
        GameAction lethal = LethalSearch.find(sandbox, room, me, s);
        if (depth < 2) { List<GameAction> few = new ArrayList<>(); if (lethal != null) few.add(lethal); if (cascade != null) few.add(cascade); return few; }
        long deadline = System.nanoTime() + (long) (s.budgetMs() * 1_000_000L * s.get("seqShare"));
        long seed = sandbox.deal();
        double triggers = s.get("seqTriggers");
        // Level 1: every first move; then each level extends the best ones by every move legal after them
        List<Screened> level = new ArrayList<>();
        for (GameAction m : firsts) {
            if (ActionType.END_TURN.is(m) || SEQUENCE.equals(m.getType())) continue;
            Screened x = screen(sandbox, room, me, List.of(m), seed, s, triggers);
            if (x != null) level.add(x);
        }
        List<Screened> singles = new ArrayList<>(level);
        List<Screened> found = new ArrayList<>();
        for (int d = 2; d <= depth && System.nanoTime() < deadline; d++) {
            level.sort(Comparator.comparingDouble(x -> -x.score()));
            List<Screened> grown = new ArrayList<>();
            for (Screened x : level.subList(0, Math.min(level.size(), d == 2 ? level.size() : keep))) {
                for (GameAction m : x.next()) {
                    if (System.nanoTime() > deadline) break;
                    List<GameAction> seq = new ArrayList<>(x.moves());
                    seq.add(m);
                    Screened y = screen(sandbox, room, me, seq, seed, s, triggers);
                    if (y != null) grown.add(y);
                }
            }
            found.addAll(grown);
            level = grown;
        }
        found.sort(Comparator.comparingDouble(x -> -x.score()));
        List<GameAction> out = new ArrayList<>(found.stream().limit(keep).map(x -> of(x.moves())).toList());
        if (s.get("seqPass") >= 0.5) {
            // seqPass: the best single moves and sequences, then the turn passed: what it keeps in the hand stays there in
            // the tries (the heuristic finishing the turn plays all it can), so keeping a card is a candidate too
            singles.sort(Comparator.comparingDouble(x -> -x.score()));
            List<List<GameAction>> bases = new ArrayList<>();
            singles.stream().limit(keep).forEach(x -> bases.add(x.moves()));
            found.stream().limit(keep).forEach(x -> bases.add(x.moves()));
            for (List<GameAction> b : bases) {
                List<GameAction> seq = new ArrayList<>(b);
                seq.add(BotPlayer.action(ActionType.END_TURN, Map.of()));
                out.add(of(seq));
            }
        }
        if (cascade != null) out.add(0, cascade);
        if (lethal != null) out.add(0, lethal);
        return out;
    }

    /** The sequence on a copy, scored right away, with the moves legal after it (null: a move was refused). */
    private static Screened screen(Sandbox sandbox, GameRoom room, String me, List<GameAction> moves, long seed, BotSettings s, double triggers) {
        GameRoom copy = sandbox.fork(room, me, new Random(seed));
        try {
            int before = copy.getTimeline().size();
            Map<String, BotPlayer> sides = Map.of(me, new HeuristicBot(s), copy.getOpponentId(me), new HeuristicBot(s));
            for (GameAction m : moves) {
                GameStateUpdate u = sandbox.act(copy, me, m);
                if (u != null && u.getError() != null) return null;
                settle(sandbox, copy, me, sides);
            }
            long fired = copy.getTimeline().subList(before, copy.getTimeline().size()).stream()
                    .filter(e -> com.drive.drive_manager.simulator.engine.GameEvent.EFFECT_ACTIVATED.equals(e.getType()) && me.equals(e.getPlayerId())).count();
            boolean mine = copy.getStatus() == GameRoom.RoomStatus.IN_PROGRESS && me.equals(copy.getCurrentTurnUserId()) && !copy.isEngineBusy();
            // hUseful: no utility that would do nothing in a sequence either (its activation would earn the trigger bonus)
            List<GameAction> next = !mine ? List.of() : LookaheadBot.mainMoves(copy, me).stream()
                    .filter(m -> s.get("hUseful") < 0.5 || Usefulness.playDoesSomething(copy, me, m, s)).toList();
            // Gems paid below 0 go to the rival for their turn
            double debt = Math.max(0, -com.drive.drive_manager.simulator.engine.EffectEngine.gemsOf(copy, me));
            // seqRollout: screened by one try (the rest of the turn and the rival's by the heuristic) instead of the table now
            double table = s.get("seqRollout") >= 0.5
                    ? new MoveTrials(s, sandbox, (r, p) -> TableScore.score(r, p, s), Long.MAX_VALUE).once(room, me, moves.size() == 1 ? moves.get(0) : of(moves), seed)
                    : TableScore.score(copy, me, s) - TableScore.overExtend(debt, copy.getPlayer(copy.getOpponentId(me)).getHand().size(), s);
            return new Screened(moves, table + triggers * fired, next);
        } finally {
            sandbox.drop(copy);
        }
    }

    /** Answers by the heuristic what is pending (prompts, chains, the rival's blocks) until the bot may move again. */
    static void settle(Sandbox sandbox, GameRoom copy, String me, Map<String, BotPlayer> sides) {
        for (int step = 0; step < MAX_SETTLE && copy.getStatus() == GameRoom.RoomStatus.IN_PROGRESS
                && (copy.getPrompt() != null || copy.isEngineBusy()); step++) {
            String seat = copy.getPrompt() != null ? copy.getPrompt().getPlayerId() : copy.getCurrentTurnUserId();
            BotPlayer side = sides.get(seat);
            GameAction next = side == null ? null : side.next(copy, seat);
            if (next == null) return;
            GameStateUpdate u = sandbox.act(copy, seat, next);
            if (u != null && u.getError() != null) side.refused(next, u.getError());
            else side.played(copy, next);
        }
    }
}
