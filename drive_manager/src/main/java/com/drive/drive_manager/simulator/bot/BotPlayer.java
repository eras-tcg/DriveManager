package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.EffectEngine;
import com.drive.drive_manager.simulator.engine.Prompt;
import com.drive.drive_manager.simulator.room.ActionType;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A side of the table played by the server (a room against the bot). It sends the same actions as a player and
 * decides only from what its own player sees: its hand, its deck top in the mulligan, the table and the open
 * choice, never the rival's hidden cards.
 * <p>
 * This base plays the least it can (phase 0): it keeps its hand, answers every choice with the fewest cards it
 * allows and ends its turn. {@link HeuristicBot} plays to win, overriding {@link #mulligan}, {@link #answer} and
 * {@link #mainMove}. {@link #next} never changes the room: the room service sends the move and tells the bot how
 * it went ({@link #played}, {@link #refused}).
 */
public class BotPlayer {

    /**
     * Moves tried in a row for one decision before the bot concedes (the engine refused them all). A refused move
     * changes nothing, so trying is cheap: enough to go through every group of a short list of cards.
     */
    static final int MAX_ATTEMPTS = 1100;
    /** Moves of one turn before the bot ends it, whatever it could still do (an effect it could repeat forever). */
    static final int MAX_MOVES_PER_TURN = 60;

    /** Pauses before a move, so the player follows it on the board. */
    static final long PAUSE_ROOM_MS = 400;
    static final long PAUSE_ANSWER_MS = 700;
    static final long PAUSE_TURN_MS = 1000;

    private final AtomicBoolean waking = new AtomicBoolean();
    /** Moves the engine refused (by {@link #key}): not tried again while the memory is about the same moment. */
    private final Set<String> refused = new HashSet<>();
    /** The moment the memory is about: the room's status, whose turn it is and the bot's deck. */
    private String moment;
    private int movesThisTurn;

    // ------------------------------------------------------------------ The move

    /** The bot's next move in the room, or null when it has nothing to do now (it waits for the other player). */
    public GameAction next(GameRoom room, String me) {
        PlayerState self = room.getPlayer(me);
        if (self == null) return null;
        remember(room, self);
        return switch (room.getStatus()) {
            case WAITING -> self.getDeckId() != null && self.getStarterCardId() != null && !self.isReady()
                    ? untried(action(ActionType.SET_READY, Map.of("ready", true))) : null;
            case SELECTING_STARTER -> room.getStarterSelections().containsKey(me) ? null
                    : self.getDeck().stream().filter(CardSlot::isStarter).findFirst()
                            .map(c -> untried(action(ActionType.SELECT_STARTER, Map.of("instanceId", c.getInstanceId())))).orElse(null);
            case MULLIGAN -> room.getMulliganReady().contains(me) ? null : untried(action("CONFIRM_MULLIGAN",
                    Map.of("toBottom", mulligan(room, self, new ArrayList<>(self.getDeck().subList(0, Math.min(5, self.getDeck().size())))))));
            case IN_PROGRESS -> inGame(room, me);
            case POST_GAME -> room.getPostGameChoices().containsKey(me) ? null : untried(action(ActionType.POST_GAME_CHOICE, Map.of("choice", "rematch")));
            default -> null;
        };
    }

    private GameAction inGame(GameRoom room, String me) {
        Prompt prompt = room.getPrompt();
        if (prompt != null) return me.equals(prompt.getPlayerId()) ? answerAction(room, me, prompt) : null;
        if (room.isEngineBusy() || !me.equals(room.getCurrentTurnUserId())) return null;
        if ("MAIN".equals(room.getCurrentPhase()) && movesThisTurn < MAX_MOVES_PER_TURN) {
            GameAction move = mainMove(room, me);
            if (move != null && !isRefused(move)) return move;
        }
        // The end phase with nothing pending passes the turn too
        return untried(action(ActionType.END_TURN, Map.of()));
    }

    /** The answer to the bot's choice: its own, else the plainest ones the prompt allows; conceding when none is left. */
    private GameAction answerAction(GameRoom room, String me, Prompt prompt) {
        List<List<String>> options = new ArrayList<>();
        List<String> own = answer(room, me, prompt);
        if (own != null) options.add(own);
        options.addAll(fallbacks(prompt));
        for (List<String> chosen : options) {
            GameAction answer = action(ActionType.RESOLVE_PROMPT, Map.of("promptId", prompt.getId(), "chosen", chosen, "order", order(prompt, chosen)));
            if (!isRefused(answer)) return answer;
        }
        return action(ActionType.SURRENDER, Map.of());
    }

    // ------------------------------------------------------------------ What a bot decides (phase 0: the least it can)

    /** Mulligan: the instance ids of the deck's top cards sent to the bottom. Phase 0 keeps them all. */
    protected List<String> mulligan(GameRoom room, PlayerState self, List<CardSlot> top) {
        return List.of();
    }

    /** The cards (or offered effects) chosen in the bot's choice; null leaves it to the fallbacks. Phase 0: the fewest. */
    protected List<String> answer(GameRoom room, String me, Prompt prompt) {
        List<String> choices = choices(prompt);
        return choices.size() < prompt.getMin() ? null : new ArrayList<>(choices.subList(0, prompt.getMin()));
    }

    /** What the bot does in its main phase, or null to end its turn. Phase 0 ends it. */
    protected GameAction mainMove(GameRoom room, String me) {
        return null;
    }

    // ------------------------------------------------------------------ Told by the room service

    /** The bot's own chance starts again from this seed (a bot that decides by chance, as one that looks ahead on copies). */
    public void seed(long seed) {
    }

    /** The move went through. */
    public void played(GameRoom room, GameAction action) {
        movesThisTurn++;
    }

    /** The engine refused the move: it is not tried again at this moment. */
    public void refused(GameAction action, String error) {
        refused.add(key(action));
    }

    /** Only one pending wake at a time: true when this call takes it. */
    boolean claimWake() {
        return waking.compareAndSet(false, true);
    }

    void releaseWake() {
        waking.set(false);
    }

    /** How long the bot waits before its next move. */
    long pause(GameRoom room, String me) {
        if (room.getStatus() != GameRoom.RoomStatus.IN_PROGRESS) return PAUSE_ROOM_MS;
        return me.equals(room.getCurrentTurnUserId()) && room.getPrompt() == null ? PAUSE_TURN_MS : PAUSE_ANSWER_MS;
    }

    // ------------------------------------------------------------------ Helpers

    /** How many moves the engine refused at this moment (a decision made before a refusal is made again). */
    protected int refusals() {
        return refused.size();
    }

    protected boolean isRefused(GameAction action) {
        return refused.contains(key(action));
    }

    /** The action, or null when the engine already refused it. */
    protected GameAction untried(GameAction action) {
        return isRefused(action) ? null : action;
    }

    /** A new moment (another turn, the next game, another deck): what was refused may work now. */
    private void remember(GameRoom room, PlayerState self) {
        String now = room.getStatus() + "|" + room.getCurrentTurnUserId() + "|" + self.getDeckId() + "|" + self.getStarterCardId();
        if (now.equals(moment)) return;
        moment = now;
        refused.clear();
        movesThisTurn = 0;
        newMoment();
    }

    /** A new moment began (another turn, the next game): what a bot remembers of the last one may go. */
    protected void newMoment() {
    }

    private static String key(GameAction action) {
        return action.getType() + new TreeMap<>(action.getPayload() == null ? Map.of() : action.getPayload());
    }

    public static GameAction action(ActionType type, Map<String, Object> payload) {
        return action(type.name(), payload);
    }

    public static GameAction action(String type, Map<String, Object> payload) {
        GameAction action = new GameAction();
        action.setType(type);
        action.setPayload(new HashMap<>(payload));
        return action;
    }

    /** What may be chosen in the prompt: its cards that are valid, and the effects it offers. */
    protected static List<String> choices(Prompt prompt) {
        List<String> ids = new ArrayList<>(prompt.getValidIds());
        if (prompt.getEffects() != null) prompt.getEffects().forEach(e -> { if (!ids.contains(e.effectId())) ids.add(e.effectId()); });
        return ids;
    }

    /**
     * Plain answers the prompt allows, tried when the bot's own is refused: none, the first ones, each one alone, as
     * many as it allows; then, for a short list, every other group of cards, smallest first (a choice with a rule of
     * its own, as materials whose levels must add up to an exact number).
     */
    private static List<List<String>> fallbacks(Prompt prompt) {
        List<String> choices = choices(prompt);
        int min = prompt.getMin();
        int max = Math.min(prompt.getMax(), choices.size());
        Set<List<String>> out = new java.util.LinkedHashSet<>();
        if (min == 0) out.add(List.of());
        if (min > 0 && choices.size() >= min) out.add(new ArrayList<>(choices.subList(0, min)));
        if (min <= 1 && max >= 1) choices.forEach(id -> out.add(List.of(id)));
        if (max > min) out.add(new ArrayList<>(choices.subList(0, max)));
        if (choices.size() <= MAX_COMBINED) {
            for (int size = Math.max(1, min); size <= max; size++) groups(choices, size, 0, new ArrayList<>(), out);
        }
        return new ArrayList<>(out);
    }

    /** Lists up to this long are searched group by group when nothing plainer is accepted (2^10 groups at most). */
    private static final int MAX_COMBINED = 10;

    private static void groups(List<String> choices, int size, int from, List<String> group, Set<List<String>> out) {
        if (group.size() == size) {
            out.add(new ArrayList<>(group));
            return;
        }
        for (int i = from; i < choices.size(); i++) {
            group.add(choices.get(i));
            groups(choices, size, i + 1, group, out);
            group.remove(group.size() - 1);
        }
    }

    /** The cards not chosen, in the order they are shown, when the prompt lets them be reordered. */
    private static List<String> order(Prompt prompt, List<String> chosen) {
        if (!prompt.isReorderRest()) return List.of();
        return prompt.getCards().stream().map(CardSlot::getInstanceId).filter(id -> !chosen.contains(id)).toList();
    }

    /** A rough worth of a card to its owner: its cost, its strength, its abilities and what it carries. */
    protected static double value(CardSlot card) {
        if (card == null) return 0;
        double v = card.getCost() == null ? 2 : card.getCost();
        if ("creature".equals(card.getCardType())) {
            v += EffectEngine.strength(card) * 0.6;
            v += EffectEngine.keywords(card).size() * 0.5;
            if (card.getLevel() != null) v += card.getLevel() * 0.3;
        }
        v += (card.getMaterials().size() + card.getResources().size()) * 0.5;
        return v;
    }

    /** One of the player's cards, in any zone (attachments included), or null. */
    protected static CardSlot own(PlayerState player, String instanceId) {
        for (List<CardSlot> zone : List.of(player.getHand(), player.getField(), player.getDeck(), player.getLifeStack(),
                player.getTributeZone(), player.getDiscardPile())) {
            for (CardSlot c : zone) {
                if (instanceId.equals(c.getInstanceId())) return c;
                for (CardSlot a : c.getMaterials()) if (instanceId.equals(a.getInstanceId())) return a;
                for (CardSlot a : c.getResources()) if (instanceId.equals(a.getInstanceId())) return a;
            }
        }
        return null;
    }

    /** A card on the player's field, or null. */
    protected static CardSlot onField(PlayerState player, String instanceId) {
        if (player == null || instanceId == null) return null;
        return player.getField().stream().filter(c -> instanceId.equals(c.getInstanceId())).findFirst().orElse(null);
    }
}
