package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.EffectEngine;
import com.drive.drive_manager.simulator.engine.LegalActions;
import com.drive.drive_manager.simulator.room.ActionType;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;
import com.drive.drive_manager.simulator.room.PlayerState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * attackFirst (the user's rule, 2026-11-04): attack while playing a card first does not make that attack better. The
 * search compares orders of the same moves by tries, and "play, then attack" and "attack, then play" reach the same
 * table when the card does not touch the attack: whichever had the luckier tries wins, and attacking first (seeing the
 * blocks and answers before spending cards) is worth something the table does not show. So when the chosen sequence
 * starts with other moves and attacks later, each of those moves is played on a copy and the attack looked at before
 * and after: the attacker (still there, may attack, its strength and abilities), the enemies that may block it (the
 * active ones of the Línea frontal, their strength) and its target. When none of that changes, the attack is made now;
 * the bot thinks again after it (the plan is made again after each move). A structure played before keeps the order: the attack may be what fires it (Volcán De Dragones, 2026-11-05).
 */
final class AttackFirst {

    private AttackFirst() {}

    /** The move to make now: the chosen one's first, or the first attack of its sequence that nothing before improves. */
    static GameAction first(Sandbox sandbox, GameRoom room, String me, GameAction chosen, BotSettings s) {
        GameAction first = SequenceSearch.first(chosen);
        if (s.get("attackFirst") < 0.5 || first == null || ActionType.ATTACK.is(first)) return first;
        List<GameAction> moves = SequenceSearch.moves(chosen);
        int at = -1;
        for (int i = 1; i < moves.size(); i++) if (ActionType.ATTACK.is(moves.get(i))) { at = i; break; }
        if (at < 0) return first;
        GameAction attack = moves.get(at);
        // A structure played before reacts to what the other cards do ("si invocas especialmente…"): the attack may be
        // what fires it, so the order stays (a creature's own "Todos los turnos" is about itself: it does not count)
        PlayerState self = room.getPlayer(me);
        for (GameAction m : moves.subList(0, at)) {
            String id = m.getString("instanceId");
            CardSlot c = id == null || self == null ? null : self.getHand().stream().filter(h -> id.equals(h.getInstanceId())).findFirst().orElse(null);
            if (c != null && "structure".equals(c.getCardType())) return first;
        }
        if (!LegalActions.of(room, me).getOrDefault(attack.getString("instanceId"), List.of()).contains("attack")) return first;
        String before = view(room, me, attack);
        GameRoom copy = sandbox.fork(room, me, new Random(sandbox.deal()));
        try {
            Map<String, BotPlayer> sides = Map.of(me, new HeuristicBot(s), copy.getOpponentId(me), new HeuristicBot(s));
            for (GameAction m : moves.subList(0, at)) {
                GameStateUpdate u = sandbox.act(copy, me, m);
                if (u != null && u.getError() != null) return first;
                SequenceSearch.settle(sandbox, copy, me, sides);
            }
            return before.equals(view(copy, me, attack)) ? attack : first;
        } finally {
            sandbox.drop(copy);
        }
    }

    /** What the attack meets: the attacker as it stands, whether it may attack, the possible blockers and the target. */
    private static String view(GameRoom room, String me, GameAction attack) {
        PlayerState self = room.getPlayer(me), rival = room.getPlayer(room.getOpponentId(me));
        String id = attack.getString("instanceId"), target = attack.getString("targetInstanceId");
        CardSlot attacker = self == null ? null : self.getField().stream().filter(c -> id.equals(c.getInstanceId())).findFirst().orElse(null);
        List<String> parts = new ArrayList<>();
        parts.add(attacker == null ? "-" : EffectEngine.strength(attacker) + " " + EffectEngine.keywords(attacker));
        parts.add(String.valueOf(LegalActions.of(room, me).getOrDefault(id, List.of()).contains("attack")));
        if (rival != null) {
            rival.getField().stream().filter(c -> "creature".equals(c.getCardType()) && EffectEngine.inFrontLine(c) && !c.isTapped())
                    .map(c -> c.getInstanceId() + ":" + EffectEngine.strength(c) + EffectEngine.keywords(c)).sorted().forEach(parts::add);
            CardSlot t = target == null ? null : rival.getField().stream().filter(c -> target.equals(c.getInstanceId())).findFirst().orElse(null);
            parts.add(t == null ? "directo:" + rival.getLifeStack().size() : "a:" + EffectEngine.strength(t) + t.isTapped());
        }
        return String.join("|", parts.stream().map(Objects::toString).toList());
    }
}
