package com.drive.drive_manager.simulator.bot

import com.drive.drive_manager.simulator.engine.EffectEngine
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState

/** What a table is worth to the look-ahead bot, by the weights of its [BotSettings] (see there). */
internal object TableScore {

    /** A game won (or, negative, lost). */
    const val WIN = 10_000.0

    /**
     * How good the table is for the bot: its side minus the rival's (times `aggression`), plus its gems. Weights:
     * [BotSettings]; a card's worth: [CardWorth].
     */
    @JvmStatic
    fun score(room: GameRoom, me: String?, w: BotSettings): Double {
        if (room.status != GameRoom.RoomStatus.IN_PROGRESS && room.winnerId != null) {
            return if (me!! == room.winnerId) WIN else -WIN
        }
        val mine = room.getPlayer(me)
        val theirs = room.getPlayer(room.getOpponentId(me))
        var s = side(room, mine, w) - w.aggression * side(room, theirs, w) + w.gems * EffectEngine.gemsOf(room, me)
        // deckClock (the user's call, 2026-11-05): only the regulatory draw at the start of a turn loses (TurnActions),
        // and one card is enough to hold. The rival with an empty deck loses at the start of its next turn, before it
        // attacks: beyond the tries (they end at the start of the bot's next turn), so it is counted here. The bot's own
        // empty deck is not: the start of its next turn is within the tries (the engine ends the game there), and with
        // its turn still ahead it may refill (Dracoventus) only then; a deck of 1 or more is worth nothing here
        val clock = w.get("deckClock")
        if (clock > 0 && theirs != null && theirs.deck.isEmpty()) s += clock * WIN
        if (w.vTop != 0.0) s += w.vTop * (dominant(mine, theirs) - w.aggression * dominant(theirs, mine))
        if (w.vLanes != 0.0) s += w.vLanes * (lanes(mine, theirs) - w.aggression * lanes(theirs, mine))
        if (mine != null && (w.hDead != 0.0 || w.hDeadSpecial != 0.0)) s -= dead(room, me, mine, w)
        // lifeMine c: its own lives counted from the last one, the k-th worth life * c^k, so with many lives a hit costs it
        // little and attacking (leaving no blocker home) pays; the rival's lives keep their full worth
        val c = w.get("lifeMine")
        if (mine != null && c > 0 && c < 1) s += w.life * (curve(c, mine.lifeStack.size) - mine.lifeStack.size)
        // lifeFoe c (the user's call, 2026-11-05): the rival's lives the same way, the last one worth life and the k-th from
        // it life * c^k, so a hit far from the win is worth little and clearing their table beats it; near the win it is
        // worth much again (the last hit is the win itself)
        val foe = w.get("lifeFoe")
        if (theirs != null && foe > 0 && foe < 1) s -= w.aggression * w.life * (curve(foe, theirs.lifeStack.size) - theirs.lifeStack.size)
        return s
    }

    /** The worth of n lives counted from the last one, in lives: 1 + c + c² + … (n terms). */
    @JvmStatic
    fun curve(c: Double, n: Int): Double {
        var f = 1.0
        var sum = 0.0
        var k = 0
        while (k < n) {
            sum += f
            k++
            f *= c
        }
        return sum
    }

    /**
     * Overextend, overExtend (the user's name, 2026-11-04): what gems paid below 0 cost at the end of its turn (they go to the rival
     * for their turn): gemDebt per gem, bent by overExtendPow (above 1: each further gem below 0 costs more) and times 1 +
     * overExtendUnc per card in the rival's hand (the more it holds, the less it is known what it does with those gems).
     */
    @JvmStatic
    fun overExtend(debt: Double, rivalHand: Int, w: BotSettings): Double {
        if (debt <= 0) return 0.0
        return w.get("gemDebt") * Math.pow(debt, w.get("overExtendPow")) * (1 + w.get("overExtendUnc") * rivalHand)
    }

    /**
     * Open lanes: the player's creatures that no active creature in the other's Línea frontal can block and win or
     * tie against (all of them when that line is empty). Removing a blocker opens lanes: it shows here before the
     * damage it lets through does.
     */
    @JvmStatic
    fun lanes(p: PlayerState?, other: PlayerState?): Int {
        if (p == null) return 0
        val wall = if (other == null) -1 else other.field
                .filter { c -> "creature" == c.cardType && EffectEngine.inFrontLine(c) && !c.tapped }
                .maxOfOrNull { c -> EffectEngine.strength(c) } ?: -1
        return p.field.count { c -> "creature" == c.cardType && EffectEngine.strength(c) > wall }
    }

    /**
     * Creatures stuck in the hand: hDead for each one the player cannot summon normally soon (no normal cost, or more
     * than its Marcador will give next turn), and hDeadSpecial more when it cannot be summoned specially either now.
     */
    @JvmStatic
    fun dead(room: GameRoom, me: String?, p: PlayerState, w: BotSettings): Double {
        val next = Math.min(5, p.resourceCount + 1)
        var s = 0.0
        for (c in p.hand) {
            if ("creature" != c.cardType) continue
            val normal = c.cost != null && c.cost!! <= next
            if (normal) continue
            s += w.hDead
            if (w.hDeadSpecial != 0.0 && !com.drive.drive_manager.simulator.engine.SpecialRequirements.canSpecial(room, me, c)) s += w.hDeadSpecial
        }
        return s
    }

    /** The player's creatures stronger than every creature of the other: no block stops them and they win any clash. */
    @JvmStatic
    fun dominant(p: PlayerState?, other: PlayerState?): Int {
        if (p == null) return 0
        val top = if (other == null) -1 else other.field.filter { c -> "creature" == c.cardType }
                .maxOfOrNull { c -> EffectEngine.strength(c) } ?: -1
        return p.field.count { c -> "creature" == c.cardType && EffectEngine.strength(c) > top }
    }

    /**
     * The hand's worth in cards. Plain: one per card. hWait h: a card is worth h^t, t the turns until it can be played
     * (its cost against the gems its Marcador will give, which grows by 1 a turn up to 5; more than 5, or only a
     * special summon, later still), so a creature it cannot play for turns weighs little and giving it up beats giving
     * up a card it can play. handDecay d: the cards, the most playable first, then worth d^k each (the k-th), so a big
     * hand adds less and less (20 cards with d=0.9 are worth 8.8). hRank: each card by its worth to keep
     * ([HandRank]: its copies, its type's crowd, its playability), before the decay.
     */
    @JvmStatic
    fun hand(p: PlayerState, w: BotSettings): Double {
        return hand(null, p, w)
    }

    @JvmStatic
    fun hand(room: GameRoom?, p: PlayerState, w: BotSettings): Double {
        val d = w.get("handDecay")
        val h = w.get("hWait")
        val rank = HandRank.on(w)
        val n = p.hand.size
        if (!(d > 0 && d < 1) && !(h > 0 && h < 1) && !rank) return n.toDouble()
        val each = if (rank) HandRank.quality(room, p, w) else DoubleArray(n)
        for (i in 0 until n) each[i] = (if (rank) each[i] else 1.0) * (if (h > 0 && h < 1) Math.pow(h, waitOf(p.hand[i], p).toDouble()) else 1.0)
        java.util.Arrays.sort(each)
        var sum = 0.0
        var f = 1.0
        for (i in n - 1 downTo 0) {
            sum += each[i] * f
            if (d > 0 && d < 1) f *= d
        }
        return sum
    }

    /** Turns until the card can be played, by its cost and the player's Marcador (an estimate). */
    @JvmStatic
    fun waitOf(c: CardSlot, p: PlayerState): Int {
        val next = Math.min(5, p.resourceCount + 1)
        val cost = c.cost
        if (cost == null) {
            // Only by a special summon: its price and a material to find
            val special = if (c.specialCost == null) 6 else c.specialCost!!
            return Math.max(1, special - next) + 2
        }
        // Beyond 5 the Marcador never pays it alone: it waits for reductions
        return if (cost <= 5) Math.max(0, cost - next) else cost - next + 2
    }

    /** What a side is worth: its lives, its hand, its field (by position), its Tributos and Descarte, a deck about to run out. */
    @JvmStatic
    fun side(p: PlayerState?, w: BotSettings): Double {
        return side(null, p, w)
    }

    /** The same, in its room (hRank's playability asks the rules; without the room it counts every card playable). */
    @JvmStatic
    fun side(room: GameRoom?, p: PlayerState?, w: BotSettings): Double {
        if (p == null) return 0.0
        var s = p.lifeStack.size * w.life + w.hand * hand(room, p, w)
        // handLow: the last cards are scarce (the user's game, 2026-11-04: it emptied its hand for little): minus handLow
        // per card short of handFloor in the hand, so giving up the last ones costs more than one card's worth
        val low = w.get("handLow")
        if (low != 0.0) s -= low * Math.max(0.0, w.get("handFloor") - p.hand.size)
        if (w.zHand != 0.0) for (c in p.hand) s += w.zHand * CardWorth.of(c, w)
        // zHandPlay: the hand's quality, each card's worth while it can be played soon (hPickWait per turn of waiting):
        // a search that brings a good card it can play is worth more than any card
        val zp = w.get("zHandPlay")
        if (zp != 0.0) for (c in p.hand) s += zp * CardWorth.of(c, w) * Math.pow(w.get("hPickWait"), waitOf(c, p).toDouble())
        if (w.uHand != 0.0 || w.uCopies != 0.0 || w.sHand != 0.0) {
            // A utility is worth holding once: each further copy of it in the hand is worth uCopies less
            val held: MutableSet<String> = java.util.HashSet()
            for (c in p.hand) {
                if ("structure" == c.cardType) s += w.sHand
                else if ("utility" == c.cardType) s += if (held.add(BotSettings.cardKey(c.cardName))) w.uHand else w.uHand - w.uCopies
            }
        }
        if (w.hCopies != 0.0) {
            // A second copy of a card in the hand adds less than the first: hCopies less for each copy beyond one
            val seen: MutableSet<String> = java.util.HashSet()
            for (c in p.hand) if (!seen.add(BotSettings.cardKey(c.cardName))) s -= w.hCopies
        }
        if (w.uTribute != 0.0) {
            // A utility in the Tributos does nothing there (designer, 2026-11-04): a loss of uTribute, damped by the copies
            // of it still to play (hand, field, deck): times uDamp for each (uDamp 0: no loss while one copy is left)
            val left: MutableMap<String, Int> = java.util.HashMap()
            for (zone in java.util.List.of(p.hand, p.field, p.deck)) {
                for (c in zone) left.merge(BotSettings.cardKey(c.cardName), 1) { a, b -> a + b }
            }
            for (c in p.tributeZone) {
                if ("utility" == c.cardType) s -= w.uTribute * Math.pow(w.uDamp, left.getOrDefault(BotSettings.cardKey(c.cardName), 0).toDouble())
            }
        }
        if (w.zTribute != 0.0 || w.hasBiases()) {
            for (c in p.tributeZone) s += w.zTribute * CardWorth.of(c, w) + w.bias(BotSettings.BIAS_TRIBUTE, c.cardName)
        }
        if (w.zDiscard != 0.0 || w.hasBiases()) {
            for (c in p.discardPile) s += w.zDiscard * CardWorth.of(c, w) + w.bias(BotSettings.BIAS_DISCARD, c.cardName)
        }
        for (c in p.field) {
            val worth = CardWorth.of(c, w)
            s += w.bias(c.cardName)
            if ("creature" != c.cardType) {
                s += if ("structure" == c.cardType) worth * w.structure + w.sField else worth * w.support
                continue
            }
            s += worth
            // vLevelSq (the user's call, 2026-11-05): a creature's level is what was put into it (an Evolución, a Ritual, its
            // stacked effects): a level 1 is little, a level 2 something, a level 4 a lot, so clearing them beats a hit far
            // from the win (Carmesí killing a Dracoventus before the lives); its square, for both sides
            val vl = w.get("vLevelSq")
            if (vl != 0.0 && c.getLevel() != null) s += vl * c.getLevel()!! * c.getLevel()!!
            // vResource (the user's st2 games, 2026-11-05): what a tower holds, each effect its Recursos give it (a dragon
            // under Ddraig gives it "Al Atacar: destruye un enemigo de fuerza 5 o menos"); worth nothing before
            val vr = w.get("vResource")
            if (vr != 0.0) for (r in c.resources!!) if (r.cardName != null) s += vr * com.drive.drive_manager.simulator.engine.CardEffects.inheritedOf(r.cardName).size
            if (EffectEngine.inFrontLine(c)) s += if (c.tapped) -w.exposed * worth else w.guard * EffectEngine.strength(c)
        }
        if (p.deck.size < 4) s -= w.lowDeck
        return s
    }
}
