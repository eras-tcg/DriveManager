package com.drive.drive_manager.simulator.bot

import com.drive.drive_manager.simulator.engine.EffectEngine
import com.drive.drive_manager.simulator.platform.Platform
import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState
import kotlin.math.max
import kotlin.math.min

/**
 * defenceFloor (the user's game, 2026-11-11: st1-st2 s13095, with 2 lives it kept Mathaus 10 and Bertalum 7 in the
 * Retaguardia against two attackers and lost the next turn): what the rival may hit with is on the table, so it is
 * counted, not left to the deals of the tries. Every creature of the rival attacks in its turn (they are all active
 * again and move up at its start); each active creature of its own in the Línea frontal stops one of them (a block,
 * even a lost one, takes no life); what is not stopped takes lives. A way to close the turn that leaves it dead on the
 * table is no candidate while another does not:
 *
 * - The move up to the Línea frontal ([advance]): a creature kept back blocks nothing, and the only reason to keep it
 *   there is the rival's removal (frontRisk), which weighs more than a life in the score. Here the rival is given a
 *   reserve too: one more hit for each creature it has, as far as its hand goes (a special summon on one that attacked
 *   attacks again). The options that survive it are kept; when none does, those that let the fewest hits through.
 * - An attack ([allows]): attacking exhausts, and an exhausted creature does not block. With no reserve (only what is
 *   on the table: a floor, not a brake on attacking): an attack is refused when after it the rival's creatures take
 *   its last life and without it they do not. What it may still summon this turn (the creatures of its hand its gems
 *   pay for) and the enemy the attack destroys are counted.
 */
internal object DefenceFloor {

    fun on(s: BotSettings): Boolean = s.get("defenceFloor") >= 0.5

    /** The lives the rival's creatures take with `blockers` creatures to stop them (the biggest hits first). */
    private fun landed(rival: PlayerState, without: String?, blockers: Int, reserve: Boolean): Int {
        var free = 0
        val stoppable: MutableList<Int> = ArrayList()
        var n = 0
        for (c in rival.field) {
            if ("creature" != c.cardType || c.instanceId == without) continue
            n++
            if (EffectEngine.keywords(c).any { k -> k.lowercase().startsWith("imbloqueable") }) free += hits(c) else stoppable.add(hits(c))
        }
        if (reserve) for (i in 0 until min(n, rival.hand.size)) stoppable.add(1)
        stoppable.sortDescending()
        var sum = free
        for (i in max(0, blockers) until stoppable.size) sum += stoppable[i]
        return sum
    }

    private fun blocks(c: CardSlot): Boolean = "creature" == c.cardType && !c.tapped

    /**
     * The options of a move up that are no worse than the rest: those that leave it a life against the rival's
     * creatures and their reserve, or (none does) those that let the fewest hits through.
     */
    fun advance(room: GameRoom, me: String?, options: Collection<List<String?>>): List<List<String?>> {
        val self = room.getPlayer(me)
        val rival = room.getPlayer(room.getOpponentId(me))
        if (self == null || rival == null || options.size < 2) return ArrayList(options)
        val lives = self.lifeStack.size
        val hits: MutableMap<List<String?>, Int> = LinkedHashMap()
        for (o in options) {
            val blockers = self.field.count { c -> blocks(c) && (EffectEngine.inFrontLine(c) || o.contains(c.instanceId)) }
            hits[o] = landed(rival, null, blockers, true)
        }
        val least = hits.values.min()
        // Enough is enough: every option that keeps a life is as good here (the tries choose among them)
        val bar = max(least, lives - 1)
        return options.filter { o -> hits[o]!! <= bar }
    }

    /** Not an attack, or one that does not leave it dead on the table when holding the attacker back would not. */
    fun allows(room: GameRoom, me: String?, move: GameAction?): Boolean {
        if (move == null || !ActionType.ATTACK.`is`(move)) return true
        val self = room.getPlayer(me)
        val rival = room.getPlayer(room.getOpponentId(me))
        if (self == null || rival == null) return true
        val attacker = self.field.firstOrNull { c -> move.getString("instanceId") == c.instanceId } ?: return true
        val lives = self.lifeStack.size
        // Every active creature of its own may be in the Línea frontal when the turn ends, and those it can still summon
        var blockers = self.field.count { c -> blocks(c) }
        var gems = EffectEngine.gemsOf(room, me)
        for (cost in self.hand.filter { c -> "creature" == c.cardType && c.cost != null }.map { c -> c.cost!! }.sorted()) {
            if (cost > gems) break
            gems -= cost
            blockers++
        }
        if (landed(rival, null, blockers, false) >= lives) return true // dead either way: the tries decide
        val targetId = move.getString("targetInstanceId")
        val target = if (targetId == null) null else rival.field.firstOrNull { c -> targetId == c.instanceId }
        val destroyed = if (target != null && EffectEngine.strength(attacker) > EffectEngine.strength(target)) targetId else null
        val after = blockers - (if (blocks(attacker)) 1 else 0)
        return landed(rival, destroyed, after, false) < lives
    }

    /** The lives a direct attack of this creature takes: 1 plus its Letalidad. */
    private fun hits(attacker: CardSlot): Int {
        var extra = 0
        for (k in EffectEngine.keywords(attacker)) {
            if (!k.lowercase().startsWith("letalidad")) continue
            val m = Platform.regex("(\\d+)").find(k)
            if (m != null) extra = max(extra, m.groupValues[1].toInt())
        }
        return 1 + extra
    }
}
