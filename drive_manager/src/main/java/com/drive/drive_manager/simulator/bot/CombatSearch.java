package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.Combat;
import com.drive.drive_manager.simulator.engine.EffectEngine;
import com.drive.drive_manager.simulator.engine.LegalActions;
import com.drive.drive_manager.simulator.engine.Prompt;
import com.drive.drive_manager.simulator.room.ActionType;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * hCombat (the user's call, 2026-11-05): a combat is a game of its own, and its outcome is foreseeable. The attacks are
 * declared one at a time, the defender answers each with one of its active creatures of the Línea frontal (which is
 * then exhausted) or with no block; so the search is a minimax over that sequence: the attacker picks the next attack
 * (an attacker and its target: the lives or an exhausted enemy) or stops, the defender its best answer. Each line is
 * worth what it leaves: the creatures each side lost (their worth, the rival's times aggression), the lives taken
 * (with the lifeFoe / lifeMine curves; all of them: the game won), minus combatCounter times the rival's best
 * counterattack in its turn (estimated by rules, see Fight#counter) against the attackers left exhausted and the
 * lives, with the creatures that did not attack as its blockers (estimated: see {@link Fight#counter}). So "I have more, I attack" no longer decides: six
 * creatures of 6 to 1 into four of 7 to 4 lose their best four for two hits and leave two to die; three stronger ones
 * against two blockers and 2 lives win either way (no block: the game; blocks: their table). The clash is by strength
 * (a tie destroys both); Letalidad adds hits; Imbloqueable cannot be blocked. The effects that change a combat are
 * read by {@link CombatTraits} (a second destruction, a destruction on attack, what a creature gives back when
 * destroyed, a summon on attack); Provocar, Reforzar and the rest the look-ahead's tries judge.
 * <p>
 * Used by the heuristic, which plays both sides in the tries: its attacks ({@link #attack}) and its blocks
 * ({@link #block}). Null when the search would take too long (more than MAX_NODES states with the counterattacks:
 * the heuristic's rules then decide). Reforzar is not seen either: a hit is a life.
 */
final class CombatSearch {

    /** At most this many creatures a side and states searched; beyond, the rules decide. */
    private static final int MAX_UNITS = 10;
    private static final int MAX_NODES = 40_000;

    /**
     * A creature in the combat. loss: what losing it costs (its worth less what it gives back when destroyed); perk: what
     * attacking with it yields anyway (a summon on attack); bonus: the level up to which it destroys one more enemy when
     * it destroys one (-1: none); killStr / killLevel: what its effect on attack destroys (-1 / -1: none, or it cannot pay).
     */
    private record Unit(String id, int str, double worth, int hits, boolean unblockable, boolean canAttack, boolean guard,
                        int level, boolean front, double loss, double perk, int bonus, int killStr, int killLevel) {
        boolean kills() { return killStr >= 0 || killLevel >= 0; }
        /** In the Línea frontal (where effects reach) and within the strength and level given (-1: any). */
        boolean within(int maxStr, int maxLevel) {
            return front && (maxStr < 0 || str <= maxStr) && (maxLevel < 0 || level <= maxLevel);
        }
    }

    /** One side attacking, the other defending, and what a line of that combat is worth to the attacker. */
    private static final class Fight {
        final List<Unit> a, d;
        /** For each attacker, the defenders it may attack (exhausted enemies), as a mask. */
        final int[] preyOf;
        final int lives;
        final double life, curve, aggression, counter, lifeOther, curveOther;
        final int livesOther;
        final BotSettings w;
        /**
         * The cards a hit on each side's lives may reveal (what the deciding bot does not see): the defender's, for the
         * attacks; the attacker's, for the counterattack. A hit takes a life only as LifeHits says: a creature stronger
         * than the damage goes back (Reforzar), a utility or structure goes to the hand.
         */
        List<int[]> poolD = List.of(), poolA = List.of();
        final Map<Long, Double> memo = new HashMap<>();
        final Map<Long, Double> counters = new HashMap<>();
        /** States searched, shared with the counterattacks' searches: beyond MAX_NODES it gives up. */
        int[] nodes = {0};
        boolean tooBig;

        Fight(List<Unit> a, List<Unit> d, int[] preyOf, int lives, int livesOther, double curve, double curveOther, double counter, BotSettings w) {
            this.a = a; this.d = d; this.preyOf = preyOf; this.lives = lives; this.livesOther = livesOther;
            this.life = w.life; this.curve = curve; this.curveOther = curveOther; this.aggression = w.aggression; this.counter = counter; this.w = w;
            this.lifeOther = w.life;
        }

        int all(int n) { return n >= 31 ? -1 : (1 << n) - 1; }

        int guards() {
            int m = 0;
            for (int k = 0; k < d.size(); k++) if (d.get(k).guard()) m |= 1 << k;
            return m;
        }

        int attackers() {
            int m = 0;
            for (int i = 0; i < a.size(); i++) if (a.get(i).canAttack()) m |= 1 << i;
            return m;
        }

        /** The attacker's best value from here: the next attack or stopping. */
        double attack(int rem, int avail, int dAlive, int aAlive, int left, int[] bestOut) {
            if (left <= 0) return TableScore.WIN;
            long key = ((long) rem) | ((long) avail << 10) | ((long) dAlive << 20) | ((long) aAlive << 30) | ((long) left << 40);
            Double cached = bestOut == null ? memo.get(key) : null;
            if (cached != null) return cached;
            if (tooBig || ++nodes[0] > MAX_NODES) { tooBig = true; return 0; }
            double best = leaf(rem, dAlive, aAlive, left);
            int bestI = -1, bestT = -1;
            for (int i = 0; i < a.size(); i++) {
                if ((rem & aAlive & (1 << i)) == 0) continue;
                // -1: the lives; else an exhausted enemy it may attack
                for (int t = -1; t < d.size(); t++) {
                    if (t >= 0 && ((preyOf[i] & dAlive & (1 << t)) == 0)) continue;
                    Unit u = a.get(i);
                    // Its effect on attack destroys an enemy first (the most valuable it reaches: maybe a blocker)
                    int alive = dAlive;
                    if (u.kills()) alive &= ~best(d, alive, u.killStr(), u.killLevel());
                    double v = u.perk() + defend(i, t, rem & ~(1 << i), avail, alive, aAlive, left);
                    if (v > best + 1e-9) { best = v; bestI = i; bestT = t; }
                }
            }
            if (bestOut != null) { bestOut[0] = bestI; bestOut[1] = bestT; }
            memo.put(key, best);
            return best;
        }

        /** The defender's best answer to attacker i on target t (-1: the lives): no block or one of its guards. */
        double defend(int i, int t, int rem, int avail, int dAlive, int aAlive, int left) {
            double best = unblocked(i, t, rem, avail, dAlive, aAlive, left);
            if (!a.get(i).unblockable()) {
                for (int k = 0; k < d.size(); k++) {
                    if ((avail & dAlive & (1 << k)) == 0) continue;
                    best = Math.min(best, clash(i, k, rem, avail & ~(1 << k), dAlive, aAlive, left));
                }
            }
            return best;
        }

        double unblocked(int i, int t, int rem, int avail, int dAlive, int aAlive, int left) {
            if (t < 0) {
                // The hit takes the lives with the chance the cards there give (Reforzar, a card to the hand)
                double e = share(poolD, a.get(i).str(), w);
                double taken = attack(rem, avail, dAlive, aAlive, left - a.get(i).hits(), null);
                return e >= 0.999 ? taken : e * taken + (1 - e) * attack(rem, avail, dAlive, aAlive, left, null);
            }
            // Its target already destroyed (by its own effect on attack): nothing to clash with
            if ((dAlive & (1 << t)) == 0) return attack(rem, avail, dAlive, aAlive, left, null);
            return clash(i, t, rem, avail, dAlive, aAlive, left);
        }

        /** Attacker i against defender k by strength: the weaker is destroyed, both on a tie. */
        double clash(int i, int k, int rem, int avail, int dAlive, int aAlive, int left) {
            int s = a.get(i).str(), o = d.get(k).str();
            if (s >= o) dAlive &= ~(1 << k);
            if (o >= s) aAlive &= ~(1 << i);
            // The one that destroys and survives may destroy one more (Mawser): the most valuable it reaches
            if (s > o && a.get(i).bonus() >= 0) dAlive &= ~best(d, dAlive, -1, a.get(i).bonus());
            if (o > s && d.get(k).bonus() >= 0) aAlive &= ~best(a, aAlive, -1, d.get(k).bonus());
            return attack(rem, avail, dAlive, aAlive, left, null);
        }

        /** What the combat stopped here is worth: losses both ways, lives taken, minus the rival's counterattack. */
        double leaf(int rem, int dAlive, int aAlive, int left) {
            double v = 0;
            for (int k = 0; k < d.size(); k++) if ((dAlive & (1 << k)) == 0) v += aggression * d.get(k).loss();
            for (int i = 0; i < a.size(); i++) if ((aAlive & (1 << i)) == 0) v -= a.get(i).loss();
            v += aggression * life * (worthOf(curve, lives) - worthOf(curve, left));
            if (counter > 0) v -= counter * counter(rem, dAlive, aAlive);
            return v;
        }

        /**
         * The rival's counterattack in its turn, estimated (a search there too would cost more than the combat): its
         * creatures left, the strongest first, each attack the most valuable of the attackers left exhausted that it beats;
         * the others go for the lives, where the creatures that did not attack (active in the Línea frontal) block the
         * strongest they beat, or any, the last lives at stake; what is left are hits. Worth to the rival: what it destroys,
         * the lives it takes (all of them: the game), minus what it loses in the blocks.
         */
        double counter(int rem, int dAlive, int aAlive) {
            int attacked = attackers() & ~rem & aAlive;
            long key = ((long) attacked) | ((long) dAlive << 10) | ((long) aAlive << 20);
            Double cached = counters.get(key);
            if (cached != null) return cached;
            List<Unit> theirs = new ArrayList<>();
            for (int k = 0; k < d.size(); k++) if ((dAlive & (1 << k)) != 0) theirs.add(d.get(k));
            theirs.sort(Comparator.comparingInt(Unit::str).reversed());
            List<Unit> prey = new ArrayList<>(), guards = new ArrayList<>();
            for (int i = 0; i < a.size(); i++) {
                if ((aAlive & (1 << i)) == 0) continue;
                if ((attacked & (1 << i)) != 0) prey.add(a.get(i)); else if (a.get(i).guard()) guards.add(a.get(i));
            }
            double v = 0;
            double left = livesOther;
            List<Unit> direct = new ArrayList<>();
            for (Unit r : theirs) {
                // Its effect on attack first, then the bonus of a creature like Mawser after what it destroys
                if (r.kills()) v += take(prey, guards, r.killStr(), r.killLevel());
                Unit best = null;
                for (Unit p : prey) if (p.str() < r.str() && (best == null || p.loss() > best.loss())) best = p;
                if (best != null) {
                    prey.remove(best);
                    v += best.loss();
                    if (r.bonus() >= 0) v += take(prey, guards, -1, r.bonus());
                } else direct.add(r);
            }
            for (Unit r : direct) {
                Unit block = null;
                for (Unit g : guards) if (g.str() > r.str() && (block == null || g.str() < block.str())) block = g;
                if (block == null && left <= r.hits() && !guards.isEmpty()) block = guards.stream().min(Comparator.comparingDouble(Unit::worth)).get();
                if (block != null && !r.unblockable()) {
                    guards.remove(block);
                    if (block.str() >= r.str()) v -= r.loss();
                    if (r.str() >= block.str()) v += block.loss();
                    if (r.str() > block.str() && r.bonus() >= 0) v += take(prey, guards, -1, r.bonus());
                    continue;
                }
                left -= r.hits() * share(poolA, r.str(), w);
                if (left <= 1e-9) { v = TableScore.WIN; break; }
            }
            if (left > 1e-9) v += life * (worthOf(curveOther, livesOther) - worthOfPart(curveOther, left));
            v = Math.max(0, v);
            counters.put(key, v);
            return v;
        }
    }

    /** The mask of the most valuable living unit within that strength and level (-1: any), or 0 when none. */
    private static int best(List<Unit> units, int alive, int maxStr, int maxLevel) {
        int at = -1;
        for (int k = 0; k < units.size(); k++) {
            if ((alive & (1 << k)) == 0 || !units.get(k).within(maxStr, maxLevel)) continue;
            if (at < 0 || units.get(k).loss() > units.get(at).loss()) at = k;
        }
        return at < 0 ? 0 : 1 << at;
    }

    /** In the counterattack: destroys the most valuable of the creatures there (exhausted or blocking) it reaches. */
    private static double take(List<Unit> prey, List<Unit> guards, int maxStr, int maxLevel) {
        Unit at = null;
        for (List<Unit> group : List.of(prey, guards)) for (Unit u : group) {
            if (u.within(maxStr, maxLevel) && (at == null || u.loss() > at.loss())) at = u;
        }
        if (at == null) return 0;
        prey.remove(at);
        guards.remove(at);
        return at.loss();
    }

    /** The same for lives in expectation (between n and n + 1: the next life's share). */
    private static double worthOfPart(double c, double n) {
        int whole = (int) Math.floor(n);
        double w0 = worthOf(c, whole), w1 = worthOf(c, whole + 1);
        return w0 + (n - whole) * (w1 - w0);
    }

    /** The chance-weighted share of a life a hit of {@code damage} takes, over the cards it may reveal (LifeHits' rule). */
    static double share(List<int[]> pool, int damage, BotSettings w) {
        if (pool.isEmpty()) return 1;
        double sum = 0;
        for (int[] x : pool) sum += x[0] == 1 ? (x[1] <= damage ? 1 : 0) : Math.max(0, w.life - w.hand) / w.life;
        return sum / pool.size();
    }

    /** What a hit on the player's lives may reveal, as the deciding bot sees it: deck and face-down lives, and the hand when it is the rival's. */
    private static List<int[]> pool(PlayerState p, boolean rival) {
        List<int[]> out = new ArrayList<>();
        List<CardSlot> cards = new ArrayList<>(p.getDeck());
        for (CardSlot l : p.getLifeStack()) if (l.isFaceDown()) cards.add(l);
        if (rival) cards.addAll(p.getHand());
        for (CardSlot c : cards) out.add(new int[] {"creature".equals(c.getCardType()) ? 1 : 0, c.getStrength() == null ? 0 : c.getStrength()});
        return out;
    }

    /** n lives counted from the last one (TableScore's curve; without one, one each). */
    private static double worthOf(double c, int n) {
        return c > 0 && c < 1 ? TableScore.curve(c, Math.max(0, n)) : Math.max(0, n);
    }

    private CombatSearch() {}

    /** The attack to make now by the search, or null to stop attacking; also null when it cannot decide (too big). */
    static GameAction attack(GameRoom room, String me, BotSettings w, boolean[] decided) {
        decided[0] = false;
        PlayerState self = room.getPlayer(me), rival = room.getPlayer(room.getOpponentId(me));
        if (self == null || rival == null) return null;
        Map<String, List<String>> legal = LegalActions.of(room, me);
        Map<String, List<String>> targets = LegalActions.attackTargets(room, me);
        List<CardSlot> mine = creatures(self), theirs = creatures(rival);
        if (mine.size() > MAX_UNITS || theirs.size() > MAX_UNITS) return null;
        List<Unit> a = new ArrayList<>();
        for (CardSlot c : mine) a.add(unit(c, self, w, legal.getOrDefault(c.getInstanceId(), List.of()).contains("attack"), guard(c)));
        List<Unit> d = new ArrayList<>();
        for (CardSlot c : theirs) d.add(unit(c, rival, w, true, guard(c)));
        int[] preyOf = new int[a.size()];
        for (int i = 0; i < a.size(); i++) {
            for (String id : targets.getOrDefault(a.get(i).id(), List.of())) {
                for (int k = 0; k < d.size(); k++) if (d.get(k).id().equals(id)) preyOf[i] |= 1 << k;
            }
        }
        Fight f = new Fight(a, d, preyOf, rival.getLifeStack().size(), self.getLifeStack().size(), w.get("lifeFoe"), w.get("lifeMine"), w.get("combatCounter"), w);
        f.poolD = pool(rival, true);
        f.poolA = pool(self, false);
        if (f.attackers() == 0) { decided[0] = true; return null; }
        int[] best = {-1, -1};
        f.attack(f.attackers(), f.guards(), f.all(d.size()), f.all(a.size()), f.lives, best);
        if (f.tooBig) return null;
        decided[0] = true;
        if (best[0] < 0) return null;
        Map<String, Object> payload = new HashMap<>();
        payload.put("instanceId", a.get(best[0]).id());
        if (best[1] >= 0) payload.put("targetInstanceId", d.get(best[1]).id());
        return BotPlayer.action(ActionType.ATTACK, payload);
    }

    /**
     * The block that leaves the defender best against this attack and the ones that may follow (the attacker's other
     * active creatures of its Línea frontal): the ids to answer with (empty: no block), or null when it cannot decide.
     */
    static List<String> block(GameRoom room, String me, Prompt prompt, BotSettings w) {
        Combat combat = room.getCombat();
        PlayerState self = room.getPlayer(me), rival = room.getPlayer(room.getOpponentId(me));
        if (combat == null || self == null || rival == null) return null;
        List<CardSlot> theirs = creatures(rival), mine = creatures(self);
        if (mine.size() > MAX_UNITS || theirs.size() > MAX_UNITS) return null;
        // The attacker's side: the one attacking now, and the active ones of its Línea frontal (may attack after)
        List<Unit> a = new ArrayList<>();
        int now = -1;
        for (CardSlot c : theirs) {
            boolean attacking = c.getInstanceId().equals(combat.getAttackerId());
            if (attacking) now = a.size();
            a.add(unit(c, rival, w, attacking || (!c.isTapped() && EffectEngine.inFrontLine(c)), guard(c)));
        }
        if (now < 0) return null;
        List<String> valid = prompt.getValidIds();
        List<Unit> d = new ArrayList<>();
        for (CardSlot c : mine) d.add(unit(c, self, w, true, valid.contains(c.getInstanceId()) || guard(c)));
        int[] preyOf = new int[a.size()];
        for (int i = 0; i < a.size(); i++) for (int k = 0; k < d.size(); k++) {
            CardSlot c = mine.get(k);
            if (c.isTapped() && EffectEngine.inFrontLine(c)) preyOf[i] |= 1 << k;
        }
        // The defender is the bot itself here: its own lives by lifeMine, the rival's (in the counterattack) by lifeFoe
        Fight f = new Fight(a, d, preyOf, self.getLifeStack().size(), rival.getLifeStack().size(), w.get("lifeMine"), w.get("lifeFoe"), w.get("combatCounter"), w);
        f.poolD = pool(self, false);
        f.poolA = pool(rival, true);
        int target = -1;
        if (combat.getTargetId() != null) for (int k = 0; k < d.size(); k++) if (d.get(k).id().equals(combat.getTargetId())) target = k;
        int rem = f.attackers() & ~(1 << now), avail = f.guards(), dAlive = f.all(d.size()), aAlive = f.all(a.size());
        // The attacker's value of each answer: the defender takes the lowest
        String chosen = null;
        double best = prompt.getMin() == 0 ? f.unblocked(now, target, rem, avail, dAlive, aAlive, f.lives) : Double.POSITIVE_INFINITY;
        if (!a.get(now).unblockable()) {
            for (int k = 0; k < d.size(); k++) {
                if (!valid.contains(d.get(k).id())) continue;
                double v = f.clash(now, k, rem, avail & ~(1 << k), dAlive, aAlive, f.lives);
                if (v < best - 1e-9) { best = v; chosen = d.get(k).id(); }
            }
        }
        if (f.tooBig || (chosen == null && prompt.getMin() > 0)) return null;
        return chosen == null ? List.of() : List.of(chosen);
    }

    private static List<CardSlot> creatures(PlayerState p) {
        return p.getField().stream().filter(c -> "creature".equals(c.getCardType()))
                .sorted(Comparator.comparingInt(EffectEngine::strength).reversed()).toList();
    }

    /** Active and in the Línea frontal: it can block. */
    private static boolean guard(CardSlot c) {
        return !c.isTapped() && EffectEngine.inFrontLine(c);
    }

    private static Unit unit(CardSlot c, PlayerState owner, BotSettings w, boolean canAttack, boolean guard) {
        boolean unblockable = EffectEngine.keywords(c).stream().anyMatch(k -> k.toLowerCase(Locale.ROOT).startsWith("imbloqueable"));
        double worth = CardWorth.of(c, w), perk = w.get("combatPerk");
        CombatTraits t = CombatTraits.of(c);
        double loss = Math.max(0, worth - t.refundCards() * w.hand - (t.refundSummon() ? perk : 0));
        // Its effect on attack only when it can pay it: the tributes to send, an ally to sacrifice
        boolean pays = t.attackKills() && owner.getTributeZone().size() >= t.attackTributes()
                && (!t.attackSacrifice() || owner.getField().stream().filter(x -> "creature".equals(x.getCardType())).count() > 1);
        return new Unit(c.getInstanceId(), EffectEngine.strength(c), worth, hits(c), unblockable, canAttack, guard,
                c.getLevel() == null ? 0 : c.getLevel(), EffectEngine.inFrontLine(c), loss, t.attackSummon() ? perk : 0,
                t.bonusLevel(), pays ? t.attackStr() : -1, pays ? t.attackLevel() : -1);
    }

    /** The lives a direct attack of this creature takes: 1 plus its Letalidad. */
    private static int hits(CardSlot attacker) {
        int extra = 0;
        for (String k : EffectEngine.keywords(attacker)) {
            if (!k.toLowerCase(Locale.ROOT).startsWith("letalidad")) continue;
            Matcher m = Pattern.compile("(\\d+)").matcher(k);
            if (m.find()) extra = Math.max(extra, Integer.parseInt(m.group(1)));
        }
        return 1 + extra;
    }
}
