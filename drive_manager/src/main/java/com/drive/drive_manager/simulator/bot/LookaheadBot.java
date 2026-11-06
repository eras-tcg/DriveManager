package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.EffectEngine;
import com.drive.drive_manager.simulator.engine.LegalActions;
import com.drive.drive_manager.simulator.engine.Prompt;
import com.drive.drive_manager.simulator.engine.PromptPurpose;
import com.drive.drive_manager.simulator.room.ActionType;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The bot that looks one move ahead (phase 2). For each move it could make now (or each answer to its choice) it
 * plays the move on a copy of the room (see {@link Sandbox}), lets {@link HeuristicBot}s play both sides until the
 * turn passes, and scores the table it is left with ({@link #score}). It makes the move with the best score;
 * the heuristic's own move is tried first and kept on a tie, and when the time runs out.
 * <p>
 * How far it looks, how many times it tries each move and what a table is worth are its {@link BotSettings}, so
 * variants can be played against each other (the bot arena) to tune them. With a {@link ValueModel} the table is
 * scored by the model (the chance of winning from it, learned from games) instead of the hand-set weights.
 */
public class LookaheadBot extends HeuristicBot {

    /** What its decisions cost: how many it looked ahead for, their time, and how many ran out of time. */
    public record Stats(int decisions, long totalNanos, long maxNanos, int outOfTime, long movesTried) {}

    /**
     * One decision it looked ahead for, as it saw it (for audits): whose turn it was, the move chosen and its card (or
     * the card asking, for an answer) and target, among how many moves and which of them (0: the heuristic's), the score
     * it expected the table to have when the look-ahead ends, and the table's score now.
     */
    public record Decision(String me, String turnOf, String type, String card, String target, int candidates, int index,
                           double expected, double now) {}

    private final BotSettings settings;
    /** The trained model that scores a table; null scores it with the weights of the settings. */
    private final ValueModel model;
    private final Sandbox sandbox = new Sandbox();
    /** The last decision, so asking again at the same moment (the room service asks before and when moving) costs nothing. */
    private String decidedAt;
    private Object decided;

    /** Told of every decision it looks ahead for; none in the server. */
    private java.util.function.Consumer<Decision> watcher;

    private int specialChances, specials, tributeChances, tributedCreatures, tributedOthers;
    private int decisions;
    private long totalNanos;
    private long maxNanos; private int outOfTime;
    /** planKeep: the rest of the plan it chose (PlanKeep). */ private final PlanKeep plan = new PlanKeep();
    private long movesTried;

    public LookaheadBot() {
        this(BotSettings.DEFAULT);
    }

    public LookaheadBot(BotSettings settings) {
        this(settings, null);
    }

    public LookaheadBot(BotSettings settings, ValueModel model) {
        super(settings);
        this.settings = settings;
        this.model = model;
    }

    public ValueModel model() {
        return model;
    }

    public BotSettings settings() {
        return settings;
    }

    @Override
    public void seed(long seed) {
        sandbox.seed(seed);
    }

    public Stats stats() {
        return new Stats(decisions, totalNanos, maxNanos, outOfTime, movesTried);
    }

    /**
     * How it used its chances (for the tests): main decisions with a special summon among the moves, and how many it
     * made; cards it sent from the hand to the Tributos when it could choose between creatures and other cards.
     */
    public record Choices(int specialChances, int specials, int tributeChances, int tributedCreatures, int tributedOthers) {}

    public Choices choices() {
        return new Choices(specialChances, specials, tributeChances, tributedCreatures, tributedOthers);
    }

    public void watch(java.util.function.Consumer<Decision> watcher) {
        this.watcher = watcher;
    }

    // ------------------------------------------------------------------ Decisions

    @Override
    protected GameAction mainMove(GameRoom room, String me) {
        String at = moment(room, "main");
        if (at.equals(decidedAt)) return (GameAction) decided;
        GameAction heuristic = super.mainMove(room, me);
        List<GameAction> candidates = new ArrayList<>();
        candidates.add(heuristic != null ? heuristic : endTurn());
        for (GameAction move : mainMoves(room, me)) if (!isRefused(move) && !same(move, candidates.get(0)) && (!on("hUseful") || Usefulness.playDoesSomething(room, me, move, settings))) candidates.add(move);
        if (heuristic != null) candidates.add(endTurn());
        if (on("hUsefulEnd")) candidates.removeIf(m -> !Usefulness.playPaysOff(room, me, m, settings)); // a utility that leaves the turn no better
        candidates.addAll(1, SequenceSearch.best(sandbox, room, me, candidates, settings)); plan.offer(room, me, candidates, settings);
        GameAction best = (GameAction) remember(at, plan.next(sandbox, room, me, best(room, me, candidates), settings));
        if (candidates.stream().anyMatch(a -> ActionType.RITUAL_SUMMON.is(a))) {
            specialChances++;
            if (best != null && ActionType.RITUAL_SUMMON.is(best)) specials++;
        }
        return best == null || ActionType.END_TURN.is(best) ? null : best;
    }

    @Override
    protected List<String> answer(GameRoom room, String me, Prompt prompt) {
        String at = moment(room, "prompt " + prompt.getId());
        @SuppressWarnings("unchecked")
        List<String> cached = at.equals(decidedAt) ? (List<String>) decided : null;
        if (cached != null) return cached;
        List<String> heuristic = super.answer(room, me, prompt);
        // hUseful: the heuristic said no to an optional effect of its own because it would do nothing
        if (on("hUseful") && optionalEffect(prompt) && heuristic != null && heuristic.isEmpty()) return heuristic;
        if (on("hUsefulEnd") && optionalEffect(prompt) && prompt.getMin() == 0 && heuristic != null && !heuristic.isEmpty() && !Usefulness.yesPaysOff(room, me, prompt, heuristic, settings)) return List.of();
        Set<List<String>> options = new LinkedHashSet<>(); if (heuristic != null) options.add(heuristic);
        List<String> choices = choices(prompt);
        PlayerState self = room.getPlayer(me);
        if (on("hTributeCreatures") && tributesFromHand(self, prompt, choices)) {
            // Which cards go to the Tributos is the rule's (the table's score cannot tell a creature there from a utility
            // kept in the hand); how many, when the card leaves it open, is tried: the first k in the rule's order
            List<String> order = choices.stream().sorted(Comparator.comparingDouble((String id) -> tributeOrder(cardIn(prompt, id)))).toList();
            for (int k = prompt.getMin(); k <= Math.min(prompt.getMax(), order.size()); k++) options.add(new ArrayList<>(order.subList(0, k)));
        } else {
            // A special summon it began is not called off unless the heuristic finds nothing to choose: in the copies the
            // heuristic summons it again for the rest of the turn, so calling it off scores as well and wins by noise
            boolean begun = PromptPurpose.SPECIAL.is(prompt.getPurpose()) || PromptPurpose.RITUAL.is(prompt.getPurpose());
            if (prompt.getMin() == 0 && !(begun && heuristic != null && !heuristic.isEmpty())) options.add(List.of());
            if (prompt.getMin() <= 1 && prompt.getMax() >= 1) choices.forEach(id -> options.add(List.of(id)));
            if (prompt.getMax() > 1 && choices.size() >= Math.max(prompt.getMin(), 2)) {
                options.add(new ArrayList<>(choices.subList(0, Math.min(prompt.getMax(), choices.size())))); AdvanceOptions.add(options, room, self, prompt, choices, settings);
            }
            // With a loss for utilities in the Tributos (or the hand's ranking, hRank), the table can tell them apart: it also tries the weakest first
            if ((settings.uTribute != 0 || HandRank.on(settings)) && tributesFromHand(self, prompt, choices)) {
                List<String> order = HandRank.on(settings) ? HandRank.weakestFirst(room, self, choices, settings) : choices.stream().sorted(Comparator.comparingDouble((String id) -> tributeOrder(cardIn(prompt, id)))).toList();
                for (int k = Math.max(1, prompt.getMin()); k <= Math.min(prompt.getMax(), order.size()); k++) options.add(new ArrayList<>(order.subList(0, k)));
            }
        }
        if (options.size() <= 1) return counted(self, prompt, choices, heuristic);
        List<GameAction> candidates = new ArrayList<>();
        for (List<String> chosen : options) {
            GameAction a = action(ActionType.RESOLVE_PROMPT, Map.of("promptId", prompt.getId(), "chosen", chosen, "order", List.of()));
            if (!isRefused(a)) candidates.add(a);
        }
        GameAction best = best(room, me, candidates);
        List<String> chosen = best == null ? heuristic : best.getStringList("chosen");
        remember(at, chosen);
        return counted(self, prompt, choices, chosen);
    }

    /** Counts what it sends from the hand to the Tributos when it could choose between creatures and other cards. */
    private List<String> counted(PlayerState self, Prompt prompt, List<String> choices, List<String> chosen) {
        if (chosen == null || !tributesFromHand(self, prompt, choices)) return chosen;
        List<CardSlot> offered = choices.stream().map(id -> cardIn(prompt, id)).filter(c -> c != null).toList();
        boolean mixed = offered.stream().anyMatch(c -> "creature".equals(c.getCardType())) && offered.stream().anyMatch(c -> !"creature".equals(c.getCardType()));
        if (!mixed) return chosen;
        tributeChances++;
        for (String id : chosen) {
            CardSlot c = cardIn(prompt, id);
            if (c != null && "creature".equals(c.getCardType())) tributedCreatures++;
            else tributedOthers++;
        }
        return chosen;
    }

    private static CardSlot cardIn(Prompt prompt, String id) {
        return prompt.getCards().stream().filter(c -> id.equals(c.getInstanceId())).findFirst().orElse(null);
    }

    /** Every move the rules allow now: each attack (to the rival and to each enemy it may attack), summon, play, Ritual and slow effect. */
    static List<GameAction> mainMoves(GameRoom room, String me) {
        List<GameAction> moves = new ArrayList<>();
        Map<String, List<String>> legal = LegalActions.of(room, me);
        Map<String, List<String>> targets = LegalActions.attackTargets(room, me);
        double x = summonX(room.getPlayer(me));
        legal.forEach((id, can) -> {
            if (can.contains("attack")) {
                moves.add(action(ActionType.ATTACK, Map.of("instanceId", id)));
                for (String target : targets.getOrDefault(id, List.of())) moves.add(action(ActionType.ATTACK, Map.of("instanceId", id, "targetInstanceId", target)));
            }
            if (can.contains("summon")) moves.add(action(ActionType.NORMAL_SUMMON, Map.of("instanceId", id, "x", x, "y", 75.0)));
            if (can.contains("play")) moves.add(action(ActionType.PLAY_CARD, Map.of("instanceId", id, "x", x, "y", 75.0)));
            if (can.contains("ritual")) moves.add(action(ActionType.RITUAL_SUMMON, Map.of("instanceId", id)));
            if (can.contains("activate")) moves.add(action(ActionType.ACTIVATE_EFFECT, Map.of("instanceId", id)));
        });
        return moves;
    }

    // ------------------------------------------------------------------ Trying moves

    /** The candidate with the best score (the first one, the heuristic's, on a tie or when time runs out): {@link MoveTrials}. */
    private GameAction best(GameRoom room, String me, List<GameAction> candidates) {
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);
        long start = System.nanoTime();
        MoveTrials trials = new MoveTrials(settings, sandbox, this::score, start + settings.budgetMs() * 1_000_000L);
        GameAction best = trials.best(room, me, MoveTrials.top(sandbox, room, me, candidates, settings));
        long spent = System.nanoTime() - start;
        decisions++;
        totalNanos += spent;
        maxNanos = Math.max(maxNanos, spent);
        if (trials.ranOutOfTime) outOfTime++;
        movesTried += trials.movesTried;
        if (watcher != null) watcher.accept(decision(room, me, best, candidates, trials.expected));
        return best;
    }

    private Decision decision(GameRoom room, String me, GameAction chosen, List<GameAction> candidates, double expected) {
        boolean answer = ActionType.RESOLVE_PROMPT.is(chosen);
        String card = answer ? (room.getPrompt() == null ? null : room.getPrompt().getSourceName()) : nameOf(room, chosen.getString("instanceId"));
        return new Decision(me, room.getCurrentTurnUserId(), chosen.getType(), card, nameOf(room, chosen.getString("targetInstanceId")),
                candidates.size(), candidates.indexOf(chosen), expected, score(room, me));
    }

    private static String nameOf(GameRoom room, String instanceId) {
        CardSlot c = instanceId == null ? null : EffectEngine.findInRoom(room, instanceId);
        return c == null ? null : c.getCardName();
    }

    // ------------------------------------------------------------------ Scoring a table

    /** The table's score for the bot: a game won or lost, else the model's chance of winning (as 0–100), else the weights. */
    private double score(GameRoom room, String me) {
        if (model == null || room.getStatus() != GameRoom.RoomStatus.IN_PROGRESS) return TableScore.score(room, me, settings);
        return model.winChance(room, me) * 100;
    }

    // ------------------------------------------------------------------ Helpers

    /** The moment of a decision: the timeline, the ids handed out, what was refused and what is decided. */
    private String moment(GameRoom room, String what) {
        return room.getEventSeq() + "|" + room.getIdSeq() + "|" + refusals() + "|" + what;
    }

    private Object remember(String at, Object decision) {
        decidedAt = at;
        decided = decision;
        return decision;
    }

    private static GameAction endTurn() {
        return action(ActionType.END_TURN, Map.of());
    }

    private static boolean same(GameAction a, GameAction b) {
        return a.getType().equals(b.getType()) && a.getPayload().equals(b.getPayload());
    }
}
