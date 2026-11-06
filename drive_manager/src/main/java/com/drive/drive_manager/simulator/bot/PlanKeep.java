package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.GameEvent;
import com.drive.drive_manager.simulator.engine.LegalActions;
import com.drive.drive_manager.simulator.room.ActionType;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * planKeep b (the user's call, 2026-11-04): the bot keeps to the plan it chose. It chooses among single moves and
 * sequences, makes the first move and thinks again; the tries are noisy, so at the next decision another plan may win
 * by luck and the first one is dropped halfway (a bait attack made and its payoff never played, a card kept for later
 * summoned anyway). So the rest of the chosen sequence is remembered, and at the next decision of the same turn, while
 * its next move is still allowed, it is a candidate again, first (it wins the ties) and with b more on every try: another
 * plan replaces it only when it is better by more than that. What happened in between (a block, an answer of the rival,
 * a life that was not removed) is in the tries of the rest as of any other candidate. A pass at the plan's end is not
 * kept (seqPass): passing is weighed afresh against what the moves made opened.
 */
final class PlanKeep {

    /** The rest of the plan, and the turn it is of (who plays it and when that turn began). */
    private List<GameAction> rest = List.of();
    private boolean soft;
    private String turn;

    /** The rest of the plan as the first candidate, with its bonus (nothing when off, done, of another turn or not allowed now). */
    void offer(GameRoom room, String me, List<GameAction> candidates, BotSettings s) {
        double bonus = s.get("planKeep");
        if (bonus <= 0 || rest.isEmpty() || !turnOf(room).equals(turn) || !allowed(room, me, rest.get(0))) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("moves", rest);
        payload.put("bonus", bonus);
        if (soft) payload.put("soft", true);
        candidates.add(0, BotPlayer.action(SequenceSearch.SEQUENCE, payload));
    }

    /** The move to make now (AttackFirst's choice from the chosen one), remembering the rest of the chosen sequence. */
    GameAction next(Sandbox sandbox, GameRoom room, String me, GameAction chosen, BotSettings s) {
        GameAction move = AttackFirst.first(sandbox, room, me, chosen, s);
        rest = List.of();
        turn = null;
        // A cascade is not kept: it is worth it only while the win or its trades are still there (a block the tries did not
        // expect breaks it), and it is offered afresh at every decision anyway
        if (s.get("planKeep") <= 0 || chosen == null || move == null || ActionType.END_TURN.is(move) || SequenceSearch.soft(chosen)) return move;
        List<GameAction> moves = new ArrayList<>(SequenceSearch.moves(chosen));
        for (int i = 0; i < moves.size(); i++) if (moves.get(i) == move) { moves.remove(i); break; }
        // A plan's passing at its end is not kept: what it opens (a free card, an ally untapped) is weighed afresh
        while (!moves.isEmpty() && ActionType.END_TURN.is(moves.get(moves.size() - 1))) moves.remove(moves.size() - 1);
        if (!moves.isEmpty()) {
            rest = List.copyOf(moves);
            soft = SequenceSearch.soft(chosen);
            turn = turnOf(room);
        }
        return move;
    }

    /** Whose turn it is and when it began (the last start of a turn in the timeline). */
    private static String turnOf(GameRoom room) {
        List<GameEvent> tl = room.getTimeline();
        long began = -1;
        for (int i = tl.size() - 1; i >= 0; i--) if (GameEvent.TURN_START.equals(tl.get(i).getType())) { began = i; break; }
        return room.getCurrentTurnUserId() + ":" + began;
    }

    /** The move is allowed now, by the rules' list of what each card may do. */
    private static boolean allowed(GameRoom room, String me, GameAction m) {
        ActionType type = ActionType.of(m);
        if (type == ActionType.END_TURN) return true;
        String id = m.getString("instanceId");
        List<String> can = id == null ? List.of() : LegalActions.of(room, me).getOrDefault(id, List.of());
        return switch (type) {
            case ATTACK -> can.contains("attack") && (m.getString("targetInstanceId") == null
                    || LegalActions.attackTargets(room, me).getOrDefault(id, List.of()).contains(m.getString("targetInstanceId")));
            case NORMAL_SUMMON -> can.contains("summon");
            case PLAY_CARD -> can.contains("play");
            case RITUAL_SUMMON -> can.contains("ritual");
            case ACTIVATE_EFFECT -> can.contains("activate");
            case null, default -> false;
        };
    }
}
