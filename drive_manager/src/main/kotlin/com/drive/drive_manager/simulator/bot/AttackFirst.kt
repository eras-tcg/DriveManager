package com.drive.drive_manager.simulator.bot

import com.drive.drive_manager.simulator.engine.EffectEngine
import com.drive.drive_manager.simulator.engine.LegalActions
import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import java.util.Random

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
internal object AttackFirst {

    /** The move to make now: the chosen one's first, or the first attack of its sequence that nothing before improves. */
    @JvmStatic
    fun first(sandbox: Sandbox, room: GameRoom, me: String?, chosen: GameAction?, s: BotSettings): GameAction? {
        val first = SequenceSearch.first(chosen)
        if (s.get("attackFirst") < 0.5 || first == null || ActionType.ATTACK.`is`(first)) return first
        val moves = SequenceSearch.moves(chosen)
        var at = -1
        for (i in 1 until moves.size) if (ActionType.ATTACK.`is`(moves[i])) { at = i; break }
        if (at < 0) return first
        val attack = moves[at]
        // A structure played before reacts to what the other cards do ("si invocas especialmente…"): the attack may be
        // what fires it, so the order stays (a creature's own "Todos los turnos" is about itself: it does not count)
        val self = room.getPlayer(me)
        for (m in moves.subList(0, at)) {
            val id = m.getString("instanceId")
            val c = if (id == null || self == null) null else self.hand.firstOrNull { h -> id == h.instanceId }
            if (c != null && "structure" == c.cardType) return first
        }
        if (!can(LegalActions.of(room, me), attack.getString("instanceId")).contains("attack")) return first
        val before = view(room, me, attack)
        val copy = sandbox.fork(room, me, Random(sandbox.deal()))
        try {
            val sides = java.util.Map.of<String?, BotPlayer>(me, HeuristicBot(s), copy.getOpponentId(me), HeuristicBot(s))
            for (m in moves.subList(0, at)) {
                val u = sandbox.act(copy, me, m)
                if (u != null && u.error != null) return first
                SequenceSearch.settle(sandbox, copy, me, sides)
            }
            return if (before == view(copy, me, attack)) attack else first
        } finally {
            sandbox.drop(copy)
        }
    }

    /** What a card may do by the rules' list (`getOrDefault`, a null id included, as the Java map answered it). */
    @Suppress("UNCHECKED_CAST")
    private fun can(byCard: Map<String, List<String>>, id: String?): List<String> {
        return (byCard as Map<String?, List<String>>).getOrDefault(id, java.util.List.of())
    }

    /** What the attack meets: the attacker as it stands, whether it may attack, the possible blockers and the target. */
    private fun view(room: GameRoom, me: String?, attack: GameAction): String {
        val self = room.getPlayer(me)
        val rival = room.getPlayer(room.getOpponentId(me))
        val id = attack.getString("instanceId")
        val target = attack.getString("targetInstanceId")
        val attacker: CardSlot? = self?.field?.firstOrNull { c -> id!! == c.instanceId }
        val parts: MutableList<String> = ArrayList()
        parts.add(if (attacker == null) "-" else EffectEngine.strength(attacker).toString() + " " + EffectEngine.keywords(attacker))
        parts.add(can(LegalActions.of(room, me), id).contains("attack").toString())
        if (rival != null) {
            rival.field.filter { c -> "creature" == c.cardType && EffectEngine.inFrontLine(c) && !c.tapped }
                    .map { c -> c.instanceId + ":" + EffectEngine.strength(c) + EffectEngine.keywords(c) }.sorted().forEach { parts.add(it) }
            val t = if (target == null) null else rival.field.firstOrNull { c -> target == c.instanceId }
            parts.add(if (t == null) "directo:" + rival.lifeStack.size else "a:" + EffectEngine.strength(t) + t.tapped)
        }
        return parts.joinToString("|")
    }
}
