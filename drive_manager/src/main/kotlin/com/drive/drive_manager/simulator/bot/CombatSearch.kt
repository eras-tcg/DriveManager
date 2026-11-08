package com.drive.drive_manager.simulator.bot

import com.drive.drive_manager.simulator.engine.EffectEngine
import com.drive.drive_manager.simulator.engine.LegalActions
import com.drive.drive_manager.simulator.engine.Prompt
import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState
import java.util.Locale
import java.util.regex.Pattern

/**
 * hCombat (the user's call, 2026-11-05): a combat is a game of its own, and its outcome is foreseeable. The attacks are
 * declared one at a time, the defender answers each with one of its active creatures of the Línea frontal (which is
 * then exhausted) or with no block; so the search is a minimax over that sequence: the attacker picks the next attack
 * (an attacker and its target: the lives or an exhausted enemy) or stops, the defender its best answer. Each line is
 * worth what it leaves: the creatures each side lost (their worth, the rival's times aggression), the lives taken
 * (with the lifeFoe / lifeMine curves; all of them: the game won), minus combatCounter times the rival's best
 * counterattack in its turn (estimated by rules, see Fight#counter) against the attackers left exhausted and the
 * lives, with the creatures that did not attack as its blockers (estimated: see [Fight.counter]). So "I have more, I attack" no longer decides: six
 * creatures of 6 to 1 into four of 7 to 4 lose their best four for two hits and leave two to die; three stronger ones
 * against two blockers and 2 lives win either way (no block: the game; blocks: their table). The clash is by strength
 * (a tie destroys both); Letalidad adds hits; Imbloqueable cannot be blocked. The effects that change a combat are
 * read by [CombatTraits] (a second destruction, a destruction on attack, what a creature gives back when
 * destroyed, a summon on attack); Provocar, Reforzar and the rest the look-ahead's tries judge.
 *
 * Used by the heuristic, which plays both sides in the tries: its attacks ([attack]) and its blocks
 * ([block]). Null when the search would take too long (more than MAX_NODES states with the counterattacks:
 * the heuristic's rules then decide). Reforzar is not seen either: a hit is a life.
 */
internal object CombatSearch {

    /** At most this many creatures a side and states searched; beyond, the rules decide. */
    private const val MAX_UNITS = 10
    private const val MAX_NODES = 40_000

    /**
     * A creature in the combat. loss: what losing it costs (its worth less what it gives back when destroyed); perk: what
     * attacking with it yields anyway (a summon on attack); bonus: the level up to which it destroys one more enemy when
     * it destroys one (-1: none); killStr / killLevel: what its effect on attack destroys (-1 / -1: none, or it cannot pay).
     */
    private data class Unit(val id: String?, val str: Int, val worth: Double, val hits: Int, val unblockable: Boolean, val canAttack: Boolean, val guard: Boolean,
                            val level: Int, val front: Boolean, val loss: Double, val perk: Double, val bonus: Int, val killStr: Int, val killLevel: Int) {
        fun kills(): Boolean = killStr >= 0 || killLevel >= 0
        /** In the Línea frontal (where effects reach) and within the strength and level given (-1: any). */
        fun within(maxStr: Int, maxLevel: Int): Boolean {
            return front && (maxStr < 0 || str <= maxStr) && (maxLevel < 0 || level <= maxLevel)
        }
    }

    /** One side attacking, the other defending, and what a line of that combat is worth to the attacker. */
    private class Fight(val a: List<Unit>, val d: List<Unit>, preyOf: IntArray, lives: Int, livesOther: Int, curve: Double, curveOther: Double, counter: Double, w: BotSettings) {
        /** For each attacker, the defenders it may attack (exhausted enemies), as a mask. */
        val preyOf: IntArray = preyOf
        val lives: Int = lives
        val life: Double = w.life
        val curve: Double = curve
        val aggression: Double = w.aggression
        val counter: Double = counter
        val lifeOther: Double = w.life
        val curveOther: Double = curveOther
        val livesOther: Int = livesOther
        val w: BotSettings = w
        /**
         * The cards a hit on each side's lives may reveal (what the deciding bot does not see): the defender's, for the
         * attacks; the attacker's, for the counterattack. A hit takes a life only as LifeHits says: a creature stronger
         * than the damage goes back (Reforzar), a utility or structure goes to the hand.
         */
        var poolD: List<IntArray> = java.util.List.of()
        var poolA: List<IntArray> = java.util.List.of()
        val memo: MutableMap<Long, Double> = HashMap()
        val counters: MutableMap<Long, Double> = HashMap()
        /** States searched, shared with the counterattacks' searches: beyond MAX_NODES it gives up. */
        var nodes = intArrayOf(0)
        var tooBig = false

        fun all(n: Int): Int = if (n >= 31) -1 else (1 shl n) - 1

        fun guards(): Int {
            var m = 0
            for (k in d.indices) if (d[k].guard) m = m or (1 shl k)
            return m
        }

        fun attackers(): Int {
            var m = 0
            for (i in a.indices) if (a[i].canAttack) m = m or (1 shl i)
            return m
        }

        /** The attacker's best value from here: the next attack or stopping. */
        fun attack(rem: Int, avail: Int, dAlive: Int, aAlive: Int, left: Int, bestOut: IntArray?): Double {
            if (left <= 0) return TableScore.WIN
            val key = rem.toLong() or (avail.toLong() shl 10) or (dAlive.toLong() shl 20) or (aAlive.toLong() shl 30) or (left.toLong() shl 40)
            val cached = if (bestOut == null) memo[key] else null
            if (cached != null) return cached
            if (tooBig || ++nodes[0] > MAX_NODES) {
                tooBig = true
                return 0.0
            }
            var best = leaf(rem, dAlive, aAlive, left)
            var bestI = -1
            var bestT = -1
            for (i in a.indices) {
                if ((rem and aAlive and (1 shl i)) == 0) continue
                // -1: the lives; else an exhausted enemy it may attack
                for (t in -1 until d.size) {
                    if (t >= 0 && ((preyOf[i] and dAlive and (1 shl t)) == 0)) continue
                    val u = a[i]
                    // Its effect on attack destroys an enemy first (the most valuable it reaches: maybe a blocker)
                    var alive = dAlive
                    if (u.kills()) alive = alive and CombatSearch.best(d, alive, u.killStr, u.killLevel).inv()
                    val v = u.perk + defend(i, t, rem and (1 shl i).inv(), avail, alive, aAlive, left)
                    if (v > best + 1e-9) {
                        best = v
                        bestI = i
                        bestT = t
                    }
                }
            }
            if (bestOut != null) {
                bestOut[0] = bestI
                bestOut[1] = bestT
            }
            memo[key] = best
            return best
        }

        /** The defender's best answer to attacker i on target t (-1: the lives): no block or one of its guards. */
        fun defend(i: Int, t: Int, rem: Int, avail: Int, dAlive: Int, aAlive: Int, left: Int): Double {
            var best = unblocked(i, t, rem, avail, dAlive, aAlive, left)
            if (!a[i].unblockable) {
                for (k in d.indices) {
                    if ((avail and dAlive and (1 shl k)) == 0) continue
                    best = Math.min(best, clash(i, k, rem, avail and (1 shl k).inv(), dAlive, aAlive, left))
                }
            }
            return best
        }

        fun unblocked(i: Int, t: Int, rem: Int, avail: Int, dAlive: Int, aAlive: Int, left: Int): Double {
            if (t < 0) {
                // The hit takes the lives with the chance the cards there give (Reforzar, a card to the hand)
                val e = share(poolD, a[i].str, w)
                val taken = attack(rem, avail, dAlive, aAlive, left - a[i].hits, null)
                return if (e >= 0.999) taken else e * taken + (1 - e) * attack(rem, avail, dAlive, aAlive, left, null)
            }
            // Its target already destroyed (by its own effect on attack): nothing to clash with
            if ((dAlive and (1 shl t)) == 0) return attack(rem, avail, dAlive, aAlive, left, null)
            return clash(i, t, rem, avail, dAlive, aAlive, left)
        }

        /** Attacker i against defender k by strength: the weaker is destroyed, both on a tie. */
        fun clash(i: Int, k: Int, rem: Int, avail: Int, dAliveIn: Int, aAliveIn: Int, left: Int): Double {
            var dAlive = dAliveIn
            var aAlive = aAliveIn
            val s = a[i].str
            val o = d[k].str
            if (s >= o) dAlive = dAlive and (1 shl k).inv()
            if (o >= s) aAlive = aAlive and (1 shl i).inv()
            // The one that destroys and survives may destroy one more (Mawser): the most valuable it reaches
            if (s > o && a[i].bonus >= 0) dAlive = dAlive and best(d, dAlive, -1, a[i].bonus).inv()
            if (o > s && d[k].bonus >= 0) aAlive = aAlive and best(a, aAlive, -1, d[k].bonus).inv()
            return attack(rem, avail, dAlive, aAlive, left, null)
        }

        /** What the combat stopped here is worth: losses both ways, lives taken, minus the rival's counterattack. */
        fun leaf(rem: Int, dAlive: Int, aAlive: Int, left: Int): Double {
            var v = 0.0
            for (k in d.indices) if ((dAlive and (1 shl k)) == 0) v += aggression * d[k].loss
            for (i in a.indices) if ((aAlive and (1 shl i)) == 0) v -= a[i].loss
            v += aggression * life * (worthOf(curve, lives) - worthOf(curve, left))
            if (counter > 0) v -= counter * counter(rem, dAlive, aAlive)
            return v
        }

        /**
         * The rival's counterattack in its turn, estimated (a search there too would cost more than the combat): its
         * creatures left, the strongest first, each attack the most valuable of the attackers left exhausted that it beats;
         * the others go for the lives, where the creatures that did not attack (active in the Línea frontal) block the
         * strongest they beat, or any, the last lives at stake; what is left are hits. Worth to the rival: what it destroys,
         * the lives it takes (all of them: the game), minus what it loses in the blocks.
         */
        fun counter(rem: Int, dAlive: Int, aAlive: Int): Double {
            val attacked = attackers() and rem.inv() and aAlive
            val key = attacked.toLong() or (dAlive.toLong() shl 10) or (aAlive.toLong() shl 20)
            val cached = counters[key]
            if (cached != null) return cached
            val theirs: MutableList<Unit> = ArrayList()
            for (k in d.indices) if ((dAlive and (1 shl k)) != 0) theirs.add(d[k])
            theirs.sortWith(Comparator.comparingInt { u: Unit -> u.str }.reversed())
            val prey: MutableList<Unit> = ArrayList()
            val guards: MutableList<Unit> = ArrayList()
            for (i in a.indices) {
                if ((aAlive and (1 shl i)) == 0) continue
                if ((attacked and (1 shl i)) != 0) prey.add(a[i]) else if (a[i].guard) guards.add(a[i])
            }
            var v = 0.0
            var left = livesOther.toDouble()
            val direct: MutableList<Unit> = ArrayList()
            for (r in theirs) {
                // Its effect on attack first, then the bonus of a creature like Mawser after what it destroys
                if (r.kills()) v += take(prey, guards, r.killStr, r.killLevel)
                var best: Unit? = null
                for (p in prey) if (p.str < r.str && (best == null || p.loss > best.loss)) best = p
                if (best != null) {
                    prey.remove(best)
                    v += best.loss
                    if (r.bonus >= 0) v += take(prey, guards, -1, r.bonus)
                } else direct.add(r)
            }
            for (r in direct) {
                var block: Unit? = null
                for (g in guards) if (g.str > r.str && (block == null || g.str < block.str)) block = g
                if (block == null && left <= r.hits && !guards.isEmpty()) block = guards.minWithOrNull(Comparator.comparingDouble { u: Unit -> u.worth })!!
                if (block != null && !r.unblockable) {
                    guards.remove(block)
                    if (block.str >= r.str) v -= r.loss
                    if (r.str >= block.str) v += block.loss
                    if (r.str > block.str && r.bonus >= 0) v += take(prey, guards, -1, r.bonus)
                    continue
                }
                left -= r.hits * share(poolA, r.str, w)
                if (left <= 1e-9) {
                    v = TableScore.WIN
                    break
                }
            }
            if (left > 1e-9) v += life * (worthOf(curveOther, livesOther) - worthOfPart(curveOther, left))
            v = Math.max(0.0, v)
            counters[key] = v
            return v
        }
    }

    /** The mask of the most valuable living unit within that strength and level (-1: any), or 0 when none. */
    private fun best(units: List<Unit>, alive: Int, maxStr: Int, maxLevel: Int): Int {
        var at = -1
        for (k in units.indices) {
            if ((alive and (1 shl k)) == 0 || !units[k].within(maxStr, maxLevel)) continue
            if (at < 0 || units[k].loss > units[at].loss) at = k
        }
        return if (at < 0) 0 else 1 shl at
    }

    /** In the counterattack: destroys the most valuable of the creatures there (exhausted or blocking) it reaches. */
    private fun take(prey: MutableList<Unit>, guards: MutableList<Unit>, maxStr: Int, maxLevel: Int): Double {
        var at: Unit? = null
        for (group in java.util.List.of<List<Unit>>(prey, guards)) for (u in group) {
            if (u.within(maxStr, maxLevel) && (at == null || u.loss > at.loss)) at = u
        }
        if (at == null) return 0.0
        prey.remove(at)
        guards.remove(at)
        return at.loss
    }

    /** The same for lives in expectation (between n and n + 1: the next life's share). */
    private fun worthOfPart(c: Double, n: Double): Double {
        val whole = Math.floor(n).toInt()
        val w0 = worthOf(c, whole)
        val w1 = worthOf(c, whole + 1)
        return w0 + (n - whole) * (w1 - w0)
    }

    /** The chance-weighted share of a life a hit of `damage` takes, over the cards it may reveal (LifeHits' rule). */
    @JvmStatic
    fun share(pool: List<IntArray>, damage: Int, w: BotSettings): Double {
        if (pool.isEmpty()) return 1.0
        var sum = 0.0
        for (x in pool) sum += if (x[0] == 1) (if (x[1] <= damage) 1.0 else 0.0) else Math.max(0.0, w.life - w.hand) / w.life
        return sum / pool.size
    }

    /** What a hit on the player's lives may reveal, as the deciding bot sees it: deck and face-down lives, and the hand when it is the rival's. */
    private fun pool(p: PlayerState, rival: Boolean): List<IntArray> {
        val out: MutableList<IntArray> = ArrayList()
        val cards: MutableList<CardSlot> = ArrayList(p.deck)
        for (l in p.lifeStack) if (l.faceDown) cards.add(l)
        if (rival) cards.addAll(p.hand)
        for (c in cards) out.add(intArrayOf(if ("creature" == c.cardType) 1 else 0, if (c.strength == null) 0 else c.strength!!))
        return out
    }

    /** n lives counted from the last one (TableScore's curve; without one, one each). */
    private fun worthOf(c: Double, n: Int): Double {
        return if (c > 0 && c < 1) TableScore.curve(c, Math.max(0, n)) else Math.max(0, n).toDouble()
    }

    /** The attack to make now by the search, or null to stop attacking; also null when it cannot decide (too big). */
    @JvmStatic
    fun attack(room: GameRoom, me: String?, w: BotSettings, decided: BooleanArray): GameAction? {
        decided[0] = false
        val self = room.getPlayer(me)
        val rival = room.getPlayer(room.getOpponentId(me))
        if (self == null || rival == null) return null
        @Suppress("UNCHECKED_CAST")
        val legal = LegalActions.of(room, me) as Map<String?, List<String>>
        @Suppress("UNCHECKED_CAST")
        val targets = LegalActions.attackTargets(room, me) as Map<String?, List<String>>
        val mine = creatures(self)
        val theirs = creatures(rival)
        if (mine.size > MAX_UNITS || theirs.size > MAX_UNITS) return null
        val a: MutableList<Unit> = ArrayList()
        for (c in mine) a.add(unit(c, self, w, legal.getOrDefault(c.instanceId, java.util.List.of()).contains("attack"), guard(c)))
        val d: MutableList<Unit> = ArrayList()
        for (c in theirs) d.add(unit(c, rival, w, true, guard(c)))
        val preyOf = IntArray(a.size)
        for (i in a.indices) {
            for (id in targets.getOrDefault(a[i].id, java.util.List.of())) {
                for (k in d.indices) if (d[k].id!! == id) preyOf[i] = preyOf[i] or (1 shl k)
            }
        }
        val f = Fight(a, d, preyOf, rival.lifeStack.size, self.lifeStack.size, w.get("lifeFoe"), w.get("lifeMine"), w.get("combatCounter"), w)
        f.poolD = pool(rival, true)
        f.poolA = pool(self, false)
        if (f.attackers() == 0) {
            decided[0] = true
            return null
        }
        val best = intArrayOf(-1, -1)
        f.attack(f.attackers(), f.guards(), f.all(d.size), f.all(a.size), f.lives, best)
        if (f.tooBig) return null
        decided[0] = true
        if (best[0] < 0) return null
        val payload: MutableMap<String, Any?> = HashMap()
        payload["instanceId"] = a[best[0]].id
        if (best[1] >= 0) payload["targetInstanceId"] = d[best[1]].id
        return BotPlayer.action(ActionType.ATTACK, payload)
    }

    /**
     * The block that leaves the defender best against this attack and the ones that may follow (the attacker's other
     * active creatures of its Línea frontal): the ids to answer with (empty: no block), or null when it cannot decide.
     */
    @JvmStatic
    fun block(room: GameRoom, me: String?, prompt: Prompt, w: BotSettings): List<String?>? {
        val combat = room.combat
        val self = room.getPlayer(me)
        val rival = room.getPlayer(room.getOpponentId(me))
        if (combat == null || self == null || rival == null) return null
        val theirs = creatures(rival)
        val mine = creatures(self)
        if (mine.size > MAX_UNITS || theirs.size > MAX_UNITS) return null
        // The attacker's side: the one attacking now, and the active ones of its Línea frontal (may attack after)
        val a: MutableList<Unit> = ArrayList()
        var now = -1
        for (c in theirs) {
            val attacking = c.instanceId!! == combat.attackerId
            if (attacking) now = a.size
            a.add(unit(c, rival, w, attacking || (!c.tapped && EffectEngine.inFrontLine(c)), guard(c)))
        }
        if (now < 0) return null
        val valid = prompt.validIds
        val d: MutableList<Unit> = ArrayList()
        for (c in mine) d.add(unit(c, self, w, true, valid.contains(c.instanceId) || guard(c)))
        val preyOf = IntArray(a.size)
        for (i in a.indices) for (k in d.indices) {
            val c = mine[k]
            if (c.tapped && EffectEngine.inFrontLine(c)) preyOf[i] = preyOf[i] or (1 shl k)
        }
        // The defender is the bot itself here: its own lives by lifeMine, the rival's (in the counterattack) by lifeFoe
        val f = Fight(a, d, preyOf, self.lifeStack.size, rival.lifeStack.size, w.get("lifeMine"), w.get("lifeFoe"), w.get("combatCounter"), w)
        f.poolD = pool(self, false)
        f.poolA = pool(rival, true)
        var target = -1
        if (combat.targetId != null) for (k in d.indices) if (d[k].id!! == combat.targetId) target = k
        val rem = f.attackers() and (1 shl now).inv()
        val avail = f.guards()
        val dAlive = f.all(d.size)
        val aAlive = f.all(a.size)
        // The attacker's value of each answer: the defender takes the lowest
        var chosen: String? = null
        var best = if (prompt.min == 0) f.unblocked(now, target, rem, avail, dAlive, aAlive, f.lives) else Double.POSITIVE_INFINITY
        if (!a[now].unblockable) {
            for (k in d.indices) {
                if (!valid.contains(d[k].id)) continue
                val v = f.clash(now, k, rem, avail and (1 shl k).inv(), dAlive, aAlive, f.lives)
                if (v < best - 1e-9) {
                    best = v
                    chosen = d[k].id
                }
            }
        }
        if (f.tooBig || (chosen == null && prompt.min > 0)) return null
        return if (chosen == null) java.util.List.of() else java.util.List.of(chosen)
    }

    private fun creatures(p: PlayerState): List<CardSlot> {
        return p.field.filter { c -> "creature" == c.cardType }
            .sortedWith(Comparator.comparingInt { c: CardSlot -> EffectEngine.strength(c) }.reversed())
    }

    /** Active and in the Línea frontal: it can block. */
    private fun guard(c: CardSlot): Boolean {
        return !c.tapped && EffectEngine.inFrontLine(c)
    }

    private fun unit(c: CardSlot, owner: PlayerState, w: BotSettings, canAttack: Boolean, guard: Boolean): Unit {
        val unblockable = EffectEngine.keywords(c).any { k -> k.lowercase(Locale.ROOT).startsWith("imbloqueable") }
        val worth = CardWorth.of(c, w)
        val perk = w.get("combatPerk")
        val t = CombatTraits.of(c)
        val loss = Math.max(0.0, worth - t.refundCards * w.hand - (if (t.refundSummon) perk else 0.0))
        // Its effect on attack only when it can pay it: the tributes to send, an ally to sacrifice
        val pays = t.attackKills() && owner.tributeZone.size >= t.attackTributes
                && (!t.attackSacrifice || owner.field.stream().filter { x -> "creature" == x.cardType }.count() > 1)
        return Unit(c.instanceId, EffectEngine.strength(c), worth, hits(c), unblockable, canAttack, guard,
                if (c.getLevel() == null) 0 else c.getLevel()!!, EffectEngine.inFrontLine(c), loss, if (t.attackSummon) perk else 0.0,
                t.bonusLevel, if (pays) t.attackStr else -1, if (pays) t.attackLevel else -1)
    }

    /** The lives a direct attack of this creature takes: 1 plus its Letalidad. */
    private fun hits(attacker: CardSlot): Int {
        var extra = 0
        for (k in EffectEngine.keywords(attacker)) {
            if (!k.lowercase(Locale.ROOT).startsWith("letalidad")) continue
            val m = Pattern.compile("(\\d+)").matcher(k)
            if (m.find()) extra = Math.max(extra, Integer.parseInt(m.group(1)))
        }
        return 1 + extra
    }
}
