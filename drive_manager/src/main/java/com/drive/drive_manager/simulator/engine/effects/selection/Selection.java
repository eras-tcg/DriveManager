package com.drive.drive_manager.simulator.engine.effects.selection;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.effects.model.Selector;
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a {@link Selector}, with the rules on what an effect may reach in one place: the Retaguardia cannot be reached by
 * the opponent's effects and a creature in the Retaguardia cannot affect enemies (rule 4.0.6.2); a card that cannot be a target
 * can still be picked by an effect that does not say "elige" (rule 11.10).
 */
public final class Selection {

    private Selection() {}

    /** The cards shown for the choice: what is in the selector's zones within reach, whether or not it can be picked. */
    public static List<CardSlot> pool(EffectContext ctx, Selector selector) {
        List<CardSlot> pool = new ArrayList<>();
        if (selector.owner() != Selector.Owner.OPPONENT) addZones(pool, ctx, ctx.player(), selector.zones(), false);
        if (selector.owner() != Selector.Owner.YOU && ctx.opponent() != null) addZones(pool, ctx, ctx.opponent(), selector.zones(), true);
        return pool;
    }

    /** The cards that can be picked now. */
    public static List<CardSlot> candidates(EffectContext ctx, Selector selector, boolean target) {
        List<CardSlot> found = new ArrayList<>();
        for (CardSlot card : pool(ctx, selector)) {
            if (!selector.filter().matches(card)) continue;
            if (selector.self() == Selector.Self.EXCLUDE && ctx.isSelf(card)) continue;
            if (target && ctx.isOpponents(card) && ctx.immune(card)) continue;
            found.add(card);
        }
        return found;
    }

    private static void addZones(List<CardSlot> into, EffectContext ctx, PlayerState whose, String zones, boolean enemy) {
        for (String raw : zones.split(",")) {
            String zone = raw.trim();
            boolean onField = zone.equals("field") || zone.equals("frontLine") || zone.equals("backLine");
            if (!onField) {
                into.addAll(ctx.zone(whose, zone));
                continue;
            }
            // Rule 4.0.6.2: effects reach only the enemies of the Línea frontal, and only from yours
            if (enemy && ctx.inRetaguardia()) continue;
            for (CardSlot card : whose.getField()) {
                boolean front = ctx.inFrontLine(card);
                if (zone.equals("frontLine") && !front) continue;
                if (zone.equals("backLine") && front) continue;
                if (enemy && !front) continue;
                into.add(card);
            }
        }
    }
}
