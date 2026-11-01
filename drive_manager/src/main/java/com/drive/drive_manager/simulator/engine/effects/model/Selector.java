package com.drive.drive_manager.simulator.engine.effects.model;

import com.drive.drive_manager.simulator.engine.Amount;
import com.drive.drive_manager.simulator.engine.CardFilter;

/**
 * Which cards an action works with: every choice of cards of the game is described this way.
 *
 * @param zones  comma separated: hand, deck, tributeZone, discardPile, lifeStack, field, frontLine, backLine
 * @param owner  whose zones
 * @param filter which of their cards
 * @param min    fewest to pick: in a cost it must be met in full or the effect is not activated; at resolution as many as there are
 * @param max    most to pick
 * @param self   what happens with the effect's own card
 */
public record Selector(String zones, Owner owner, CardFilter filter, Amount min, Amount max, Self self) {

    public enum Owner { YOU, OPPONENT, BOTH }

    public enum Self {
        ANY,
        /** "otra", "otras": never the effect's own card. */
        EXCLUDE,
        /** "incluida esta carta": it must be among those picked. */
        REQUIRE
    }

    public Selector {
        filter = filter == null ? CardFilter.any() : filter;
        self = self == null ? Self.ANY : self;
    }

    /** Exactly {@code count} of yours. */
    public static Selector yours(String zones, CardFilter filter, int count) {
        return new Selector(zones, Owner.YOU, filter, new Amount.Fixed(count), new Amount.Fixed(count), Self.ANY);
    }

    /** Exactly {@code count} enemies: the opponent's Línea frontal. */
    public static Selector enemies(CardFilter filter, int count) {
        return new Selector("frontLine", Owner.OPPONENT, filter, new Amount.Fixed(count), new Amount.Fixed(count), Self.ANY);
    }

    /** "hasta N": none is fine. */
    public Selector upTo() {
        return new Selector(zones, owner, filter, new Amount.Fixed(0), max, self);
    }

    public Selector with(Self self) {
        return new Selector(zones, owner, filter, min, max, self);
    }
}
