package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.GameEvent;
import com.drive.drive_manager.simulator.room.CardLocator;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;
import com.drive.drive_manager.simulator.room.Zone;

import java.util.ArrayList;
import java.util.List;

/**
 * lifeExp: a hit on a hidden life is worth what such a hit is worth on average, not what the one card dealt in that
 * try made of it. By the rules (CombatRules.directDamage) the life a hit of D removes is a loss for its owner only if
 * it is a creature of strength D or less (to the Tributos); a stronger creature goes back as a life (Reforzar: no
 * life lost) and a utility or structure goes to the hand. The average is over the cards the hit could have been: the
 * defender's deck, face-down lives and, for the rival, its hand (what the bot does not know). So a weak attacker into
 * lives of big creatures is worth little, a strong one much, and the luck of one deal no longer swings a try.
 * lifeCurveExp (2026-11-05): with lifeFoe / lifeMine the table values the next life life * c^(lives left), not life;
 * the correction took life off and so undid more than the table had given (a hit far from the win came out at about
 * nothing: Armadura De Plata thrown at Krokler looked as good). Then the life lost is valued with the same curve.
 */
final class LifeHits {

    private LifeHits() {}

    /** The sum, over the hits on lives in a try since {@code from}, of the average hit's value minus the one dealt. */
    static double correction(GameRoom copy, String me, int from, BotSettings w) {
        List<GameEvent> tl = copy.getTimeline();
        double c = 0;
        for (int i = from; i < tl.size(); i++) {
            GameEvent e = tl.get(i);
            if (!GameEvent.LIFE_DAMAGE.equals(e.getType()) || e.getData() == null) continue;
            Object d = e.getData().get("damage"), def = e.getData().get("defenderId");
            if (!(d instanceof Number dmg) || def == null) continue;
            PlayerState defender = copy.getPlayer(String.valueOf(def));
            CardSlot life = find(defender, e.getCardInstanceId());
            if (defender == null || life == null) continue;
            List<CardSlot> pool = new ArrayList<>(defender.getDeck());
            for (CardSlot l : defender.getLifeStack()) if (l.isFaceDown()) pool.add(l);
            if (!me.equals(defender.getUserId())) pool.addAll(defender.getHand());
            pool.add(life);
            double c0 = w.get(me.equals(defender.getUserId()) ? "lifeMine" : "lifeFoe");
            double worth = w.get("lifeCurveExp") >= 0.5 && c0 > 0 && c0 < 1 ? w.life * Math.pow(c0, defender.getLifeStack().size()) : w.life;
            double avg = pool.stream().mapToDouble(x -> value(x, dmg.intValue(), w, worth)).average().orElse(0);
            double diff = avg - value(life, dmg.intValue(), w, worth);
            // For the bot: its hits on the rival count for it (times aggression), the rival's on it against it
            c += me.equals(defender.getUserId()) ? -diff : w.aggression * diff;
        }
        return c;
    }

    /** What a hit of {@code damage} that reveals this card takes from its owner. */
    private static double value(CardSlot c, int damage, BotSettings w, double life) {
        if ("creature".equals(c.getCardType())) {
            return c.getStrength() == null || c.getStrength() <= damage ? life : 0;
        }
        return life - w.hand;
    }

    /** Where a hit life may end up (or be): tributes, hand, lives, field, discard pile. */
    private static final List<Zone> SEARCHED = List.of(Zone.TRIBUTE_ZONE, Zone.HAND, Zone.LIFE_STACK, Zone.FIELD, Zone.DISCARD_PILE);

    private static CardSlot find(PlayerState p, String id) {
        return CardLocator.find(p, id, SEARCHED, false);
    }
}
