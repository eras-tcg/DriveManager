package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.EffectEngine;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;

/** What a table is worth to the look-ahead bot, by the weights of its {@link BotSettings} (see there). */
final class TableScore {

    /** A game won (or, negative, lost). */
    static final double WIN = 10_000;

    private TableScore() {}

    /**
     * How good the table is for the bot: its side minus the rival's (times {@code aggression}), plus its gems. Weights:
     * {@link BotSettings}; a card's worth: {@link CardWorth}.
     */
    static double score(GameRoom room, String me, BotSettings w) {
        if (room.getStatus() != GameRoom.RoomStatus.IN_PROGRESS && room.getWinnerId() != null) {
            return me.equals(room.getWinnerId()) ? WIN : -WIN;
        }
        PlayerState mine = room.getPlayer(me), theirs = room.getPlayer(room.getOpponentId(me));
        double s = side(room, mine, w) - w.aggression * side(room, theirs, w) + w.gems * EffectEngine.gemsOf(room, me);
        // deckClock (the user's call, 2026-11-05): only the regulatory draw at the start of a turn loses (TurnActions),
        // and one card is enough to hold. The rival with an empty deck loses at the start of its next turn, before it
        // attacks: beyond the tries (they end at the start of the bot's next turn), so it is counted here. The bot's own
        // empty deck is not: the start of its next turn is within the tries (the engine ends the game there), and with
        // its turn still ahead it may refill (Dracoventus) only then; a deck of 1 or more is worth nothing here
        double clock = w.get("deckClock");
        if (clock > 0 && theirs != null && theirs.getDeck().isEmpty()) s += clock * WIN;
        if (w.vTop != 0) s += w.vTop * (dominant(mine, theirs) - w.aggression * dominant(theirs, mine));
        if (w.vLanes != 0) s += w.vLanes * (lanes(mine, theirs) - w.aggression * lanes(theirs, mine));
        if (mine != null && (w.hDead != 0 || w.hDeadSpecial != 0)) s -= dead(room, me, mine, w);
        // lifeMine c: its own lives counted from the last one, the k-th worth life * c^k, so with many lives a hit costs it
        // little and attacking (leaving no blocker home) pays; the rival's lives keep their full worth
        double c = w.get("lifeMine");
        if (mine != null && c > 0 && c < 1) s += w.life * (curve(c, mine.getLifeStack().size()) - mine.getLifeStack().size());
        // lifeFoe c (the user's call, 2026-11-05): the rival's lives the same way, the last one worth life and the k-th from
        // it life * c^k, so a hit far from the win is worth little and clearing their table beats it; near the win it is
        // worth much again (the last hit is the win itself)
        double foe = w.get("lifeFoe");
        if (theirs != null && foe > 0 && foe < 1) s -= w.aggression * w.life * (curve(foe, theirs.getLifeStack().size()) - theirs.getLifeStack().size());
        return s;
    }

    /** The worth of n lives counted from the last one, in lives: 1 + c + c² + … (n terms). */
    static double curve(double c, int n) {
        double f = 1, sum = 0;
        for (int k = 0; k < n; k++, f *= c) sum += f;
        return sum;
    }

    /**
     * Overextend, overExtend (the user's name, 2026-11-04): what gems paid below 0 cost at the end of its turn (they go to the rival
     * for their turn): gemDebt per gem, bent by overExtendPow (above 1: each further gem below 0 costs more) and times 1 +
     * overExtendUnc per card in the rival's hand (the more it holds, the less it is known what it does with those gems).
     */
    static double overExtend(double debt, int rivalHand, BotSettings w) {
        if (debt <= 0) return 0;
        return w.get("gemDebt") * Math.pow(debt, w.get("overExtendPow")) * (1 + w.get("overExtendUnc") * rivalHand);
    }

    /**
     * Open lanes: the player's creatures that no active creature in the other's Línea frontal can block and win or
     * tie against (all of them when that line is empty). Removing a blocker opens lanes: it shows here before the
     * damage it lets through does.
     */
    static int lanes(PlayerState p, PlayerState other) {
        if (p == null) return 0;
        int wall = other == null ? -1 : other.getField().stream()
                .filter(c -> "creature".equals(c.getCardType()) && EffectEngine.inFrontLine(c) && !c.isTapped())
                .mapToInt(EffectEngine::strength).max().orElse(-1);
        return (int) p.getField().stream().filter(c -> "creature".equals(c.getCardType()) && EffectEngine.strength(c) > wall).count();
    }

    /**
     * Creatures stuck in the hand: hDead for each one the player cannot summon normally soon (no normal cost, or more
     * than its Marcador will give next turn), and hDeadSpecial more when it cannot be summoned specially either now.
     */
    static double dead(GameRoom room, String me, PlayerState p, BotSettings w) {
        int next = Math.min(5, p.getResourceCount() + 1);
        double s = 0;
        for (CardSlot c : p.getHand()) {
            if (!"creature".equals(c.getCardType())) continue;
            boolean normal = c.getCost() != null && c.getCost() <= next;
            if (normal) continue;
            s += w.hDead;
            if (w.hDeadSpecial != 0 && !com.drive.drive_manager.simulator.engine.SpecialRequirements.canSpecial(room, me, c)) s += w.hDeadSpecial;
        }
        return s;
    }

    /** The player's creatures stronger than every creature of the other: no block stops them and they win any clash. */
    static int dominant(PlayerState p, PlayerState other) {
        if (p == null) return 0;
        int top = other == null ? -1 : other.getField().stream().filter(c -> "creature".equals(c.getCardType()))
                .mapToInt(EffectEngine::strength).max().orElse(-1);
        return (int) p.getField().stream().filter(c -> "creature".equals(c.getCardType()) && EffectEngine.strength(c) > top).count();
    }

    /**
     * The hand's worth in cards. Plain: one per card. hWait h: a card is worth h^t, t the turns until it can be played
     * (its cost against the gems its Marcador will give, which grows by 1 a turn up to 5; more than 5, or only a
     * special summon, later still), so a creature it cannot play for turns weighs little and giving it up beats giving
     * up a card it can play. handDecay d: the cards, the most playable first, then worth d^k each (the k-th), so a big
     * hand adds less and less (20 cards with d=0.9 are worth 8.8). hRank: each card by its worth to keep
     * ({@link HandRank}: its copies, its type's crowd, its playability), before the decay.
     */
    static double hand(PlayerState p, BotSettings w) {
        return hand(null, p, w);
    }

    static double hand(GameRoom room, PlayerState p, BotSettings w) {
        double d = w.get("handDecay"), h = w.get("hWait");
        boolean rank = HandRank.on(w);
        int n = p.getHand().size();
        if (!(d > 0 && d < 1) && !(h > 0 && h < 1) && !rank) return n;
        double[] each = rank ? HandRank.quality(room, p, w) : new double[n];
        for (int i = 0; i < n; i++) each[i] = (rank ? each[i] : 1) * (h > 0 && h < 1 ? Math.pow(h, waitOf(p.getHand().get(i), p)) : 1);
        java.util.Arrays.sort(each);
        double sum = 0, f = 1;
        for (int i = n - 1; i >= 0; i--) {
            sum += each[i] * f;
            if (d > 0 && d < 1) f *= d;
        }
        return sum;
    }

    /** Turns until the card can be played, by its cost and the player's Marcador (an estimate). */
    static int waitOf(CardSlot c, PlayerState p) {
        int next = Math.min(5, p.getResourceCount() + 1);
        Integer cost = c.getCost();
        if (cost == null) {
            // Only by a special summon: its price and a material to find
            int special = c.getSpecialCost() == null ? 6 : c.getSpecialCost();
            return Math.max(1, special - next) + 2;
        }
        // Beyond 5 the Marcador never pays it alone: it waits for reductions
        return cost <= 5 ? Math.max(0, cost - next) : cost - next + 2;
    }

    /** What a side is worth: its lives, its hand, its field (by position), its Tributos and Descarte, a deck about to run out. */
    static double side(PlayerState p, BotSettings w) {
        return side(null, p, w);
    }

    /** The same, in its room (hRank's playability asks the rules; without the room it counts every card playable). */
    static double side(GameRoom room, PlayerState p, BotSettings w) {
        if (p == null) return 0;
        double s = p.getLifeStack().size() * w.life + w.hand * hand(room, p, w);
        // handLow: the last cards are scarce (the user's game, 2026-11-04: it emptied its hand for little): minus handLow
        // per card short of handFloor in the hand, so giving up the last ones costs more than one card's worth
        double low = w.get("handLow");
        if (low != 0) s -= low * Math.max(0, w.get("handFloor") - p.getHand().size());
        if (w.zHand != 0) for (CardSlot c : p.getHand()) s += w.zHand * CardWorth.of(c, w);
        // zHandPlay: the hand's quality, each card's worth while it can be played soon (hPickWait per turn of waiting):
        // a search that brings a good card it can play is worth more than any card
        double zp = w.get("zHandPlay");
        if (zp != 0) for (CardSlot c : p.getHand()) s += zp * CardWorth.of(c, w) * Math.pow(w.get("hPickWait"), waitOf(c, p));
        if (w.uHand != 0 || w.uCopies != 0 || w.sHand != 0) {
            // A utility is worth holding once: each further copy of it in the hand is worth uCopies less
            java.util.Set<String> held = new java.util.HashSet<>();
            for (CardSlot c : p.getHand()) {
                if ("structure".equals(c.getCardType())) s += w.sHand;
                else if ("utility".equals(c.getCardType())) s += held.add(BotSettings.cardKey(c.getCardName())) ? w.uHand : w.uHand - w.uCopies;
            }
        }
        if (w.hCopies != 0) {
            // A second copy of a card in the hand adds less than the first: hCopies less for each copy beyond one
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (CardSlot c : p.getHand()) if (!seen.add(BotSettings.cardKey(c.getCardName()))) s -= w.hCopies;
        }
        if (w.uTribute != 0) {
            // A utility in the Tributos does nothing there (designer, 2026-11-04): a loss of uTribute, damped by the copies
            // of it still to play (hand, field, deck): times uDamp for each (uDamp 0: no loss while one copy is left)
            java.util.Map<String, Integer> left = new java.util.HashMap<>();
            for (java.util.List<CardSlot> zone : java.util.List.of(p.getHand(), p.getField(), p.getDeck())) {
                for (CardSlot c : zone) left.merge(BotSettings.cardKey(c.getCardName()), 1, Integer::sum);
            }
            for (CardSlot c : p.getTributeZone()) {
                if ("utility".equals(c.getCardType())) s -= w.uTribute * Math.pow(w.uDamp, left.getOrDefault(BotSettings.cardKey(c.getCardName()), 0));
            }
        }
        if (w.zTribute != 0 || w.hasBiases()) {
            for (CardSlot c : p.getTributeZone()) s += w.zTribute * CardWorth.of(c, w) + w.bias(BotSettings.BIAS_TRIBUTE, c.getCardName());
        }
        if (w.zDiscard != 0 || w.hasBiases()) {
            for (CardSlot c : p.getDiscardPile()) s += w.zDiscard * CardWorth.of(c, w) + w.bias(BotSettings.BIAS_DISCARD, c.getCardName());
        }
        for (CardSlot c : p.getField()) {
            double worth = CardWorth.of(c, w);
            s += w.bias(c.getCardName());
            if (!"creature".equals(c.getCardType())) {
                s += "structure".equals(c.getCardType()) ? worth * w.structure + w.sField : worth * w.support;
                continue;
            }
            s += worth;
            // vLevelSq (the user's call, 2026-11-05): a creature's level is what was put into it (an Evolución, a Ritual, its
            // stacked effects): a level 1 is little, a level 2 something, a level 4 a lot, so clearing them beats a hit far
            // from the win (Carmesí killing a Dracoventus before the lives); its square, for both sides
            double vl = w.get("vLevelSq");
            if (vl != 0 && c.getLevel() != null) s += vl * c.getLevel() * c.getLevel();
            // vResource (the user's st2 games, 2026-11-05): what a tower holds, each effect its Recursos give it (a dragon
            // under Ddraig gives it "Al Atacar: destruye un enemigo de fuerza 5 o menos"); worth nothing before
            double vr = w.get("vResource");
            if (vr != 0) for (CardSlot r : c.getResources()) if (r.getCardName() != null) s += vr * com.drive.drive_manager.simulator.engine.CardEffects.inheritedOf(r.getCardName()).size();
            if (EffectEngine.inFrontLine(c)) s += c.isTapped() ? -w.exposed * worth : w.guard * EffectEngine.strength(c);
        }
        if (p.getDeck().size() < 4) s -= w.lowDeck;
        return s;
    }
}
