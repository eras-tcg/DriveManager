package com.drive.drive_manager.simulator.bot

import com.drive.drive_manager.simulator.engine.EffectEngine
import com.drive.drive_manager.simulator.engine.GameRandom
import com.drive.drive_manager.simulator.engine.PromptPurpose
import com.drive.drive_manager.simulator.engine.frozenListOf
import com.drive.drive_manager.simulator.engine.frozenMapOf
import com.drive.drive_manager.simulator.platform.AtomicCounter
import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState
import kotlin.jvm.JvmField
import kotlin.math.min

/**
 * attackFloor (the user's call, 2026-11-11): an attack is a candidate only if it is no gift in the worst case. The
 * look-ahead judged an attack against the one block it expected (the heuristic's) and by all it projected after it; a
 * rival that blocked otherwise took the attacker for nothing (measured: 33 of 236 attacks against a rival that blocks
 * by other rules). The blocks the rival may answer with are on the table, so nothing is guessed: the attack is played
 * on a copy once for each of them (no block, and each creature it may block with), the combat resolved by the engine
 * (what else it opens answered by the heuristic), and the worst of those outcomes kept. It passes when, in that worst
 * case, the material it is left with (each side's lives, hand and creatures on the field, its own minus the rival's) is
 * no lower than before by more than `floorSlack`, or the game is won.
 *
 * A floor, not a choice: what passes goes on to the tries, which choose. It sees only what is on the table (a card
 * from the rival's hand, a life that comes back, are the tries' to weigh), and one attack at a time: a bait (a
 * creature given up to exhaust a blocker for the next attack) does not pass, but a win found by [LethalSearch] does.
 * One per decision: its answers are kept by attacker and target.
 *
 * What keeps it cheap: an attacker stronger than every creature that may block it and than its target cannot be lost in
 * a clash, so it passes with no copy; and a blocked attack is a clash with the blocker whatever it was aimed at, so
 * each block's outcome is found once per attacker and shared by its attacks on every target (only the unblocked
 * outcome is the target's own). The heuristic that answers the rest in the copies does not check its utilities
 * (hUseful): no copies within copies.
 */
internal class AttackFloor(private val sandbox: Sandbox, private val room: GameRoom, private val me: String?, private val s: BotSettings) {

    private val seed = sandbox.deal()
    private val slack = s.get("floorSlack")
    private val known: MutableMap<String, Boolean> = HashMap()
    /** The outcome of each attacker blocked by each blocker ("attacker|blocker"), whatever the attack's target. */
    private val blockedBy: MutableMap<String, Double> = HashMap()
    private val plain = s.with("hUseful=0,hUsefulEnd=0")

    /** The candidate may be tried: not an attack (a sequence: its first move), a win found by the engine, or an attack that passes. */
    fun admits(candidate: GameAction?): Boolean {
        if (candidate == null || candidate.payload!![LETHAL] == true) return true
        return passes(SequenceSearch.first(candidate))
    }

    /** The move is not an attack, or its worst outcome over the rival's blocks loses no more than the slack. */
    fun passes(move: GameAction?): Boolean {
        if (move == null || !ActionType.ATTACK.`is`(move)) return true
        val key = move.getString("instanceId") + ">" + move.getString("targetInstanceId")
        return known.getOrPut(key) {
            CHECKS.incrementAndGet()
            if (cannotBeLost(move)) { SAFE.incrementAndGet(); true } else worst(move) >= -slack - 1e-9
        }
    }

    /** The attacker is stronger than every active creature of the rival's Línea frontal (or cannot be blocked) and than its target. */
    private fun cannotBeLost(attack: GameAction): Boolean {
        val self = room.getPlayer(me)
        val rival = room.getPlayer(room.getOpponentId(me))
        val attacker = self?.field?.firstOrNull { c -> attack.getString("instanceId") == c.instanceId }
        if (attacker == null || rival == null) return false
        val power = EffectEngine.strength(attacker)
        val targetId = attack.getString("targetInstanceId")
        val target = if (targetId == null) null else rival.field.firstOrNull { c -> targetId == c.instanceId }
        if (targetId != null && (target == null || EffectEngine.strength(target) >= power)) return false
        if (EffectEngine.keywords(attacker).any { k -> k.lowercase().startsWith("imbloqueable") }) return true
        return rival.field.none { c -> "creature" == c.cardType && !c.tapped && EffectEngine.inFrontLine(c) && EffectEngine.strength(c) >= power }
    }

    /** The lowest change of material over every block the rival may answer with (the engine refusing the attack: none). */
    private fun worst(attack: GameAction): Double {
        val attacker = attack.getString("instanceId")
        val blocks: MutableList<List<String?>> = ArrayList()
        val first = outcome(attack, null, blocks) ?: return 0.0
        var worst = first
        for (i in blocks.indices) {
            val block = blocks[i]
            if (block.isEmpty()) continue
            val key = attacker + "|" + block[0]
            val v = blockedBy[key] ?: (if (i == 0) first else outcome(attack, block, null))
            if (v == null) continue
            blockedBy[key] = v
            worst = min(worst, v)
        }
        return worst
    }

    /**
     * The attack on a copy, the rival's block answered with `block` (null: the first it has, and `found` is told
     * all of them), the rest by the heuristic until the table is quiet: the material after minus before; null: refused.
     */
    private fun outcome(attack: GameAction, block: List<String?>?, found: MutableList<List<String?>>?): Double? {
        FORKS.incrementAndGet()
        val copy = sandbox.fork(room, me, GameRandom(seed))
        try {
            val before = material(copy)
            val u = sandbox.act(copy, me, attack)
            if (u != null && u.error != null) return null
            val sides = frozenMapOf<String?, BotPlayer>(me, HeuristicBot(plain), copy.getOpponentId(me), HeuristicBot(plain))
            var blocked = false
            for (step in 0 until MAX_STEPS) {
                val prompt = copy.prompt
                if (copy.status != GameRoom.RoomStatus.IN_PROGRESS || (prompt == null && !copy.isEngineBusy())) break
                val seat = if (prompt != null) prompt.playerId else copy.currentTurnUserId
                val side = sides[seat]
                var next: GameAction? = null
                if (!blocked && prompt != null && me != seat && PromptPurpose.BLOCK.`is`(prompt.purpose)) {
                    blocked = true
                    val options: MutableList<List<String?>> = ArrayList()
                    if (prompt.min == 0) options.add(frozenListOf())
                    for (id in prompt.validIds) options.add(frozenListOf(id))
                    found?.addAll(options)
                    val chosen = block ?: options.firstOrNull()
                    if (chosen != null) next = BotPlayer.action(ActionType.RESOLVE_PROMPT,
                            frozenMapOf<String, Any?>("promptId", prompt.id, "chosen", chosen, "order", frozenListOf<Any>()))
                }
                val answered = next != null
                if (next == null) next = (if (side == null) null else side.next(copy, seat)) ?: break
                val r = sandbox.act(copy, seat, next)
                if (answered) continue
                if (r != null && r.error != null) side!!.refused(next, r.error) else side!!.played(copy, next)
            }
            return material(copy) - before
        } finally {
            sandbox.drop(copy)
        }
    }

    /** The bot's material minus the rival's; the game over: won or lost. */
    private fun material(copy: GameRoom): Double {
        if (copy.status != GameRoom.RoomStatus.IN_PROGRESS && copy.winnerId != null) return if (me == copy.winnerId) TableScore.WIN else -TableScore.WIN
        return side(copy.getPlayer(me)) - side(copy.getPlayer(copy.getOpponentId(me)))
    }

    /** A side's lives, cards in the hand and creatures on the field, by the weights of the table's score. */
    private fun side(p: PlayerState?): Double {
        if (p == null) return 0.0
        var v = p.lifeStack.size * s.life + p.hand.size * s.hand
        for (c: CardSlot in p.field) if ("creature" == c.cardType) v += CardWorth.of(c, s)
        return v
    }

    companion object {
        /** In a sequence's payload: a win found by the engine ([LethalSearch]), not held to the floor. */
        const val LETHAL = "lethal"
        /** Moves played at most to resolve one attack on a copy. */
        private const val MAX_STEPS = 200
        /** For the bot runs: attacks asked about, passed with no copy, and copies played. */
        @JvmField val CHECKS = AtomicCounter()
        @JvmField val SAFE = AtomicCounter()
        @JvmField val FORKS = AtomicCounter()

        /** The floor of this decision, or null when off (attackFloor below 0.5). */
        fun of(sandbox: Sandbox, room: GameRoom, me: String?, s: BotSettings): AttackFloor? {
            return if (s.get("attackFloor") >= 0.5) AttackFloor(sandbox, room, me, s) else null
        }
    }
}
