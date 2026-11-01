package com.drive.drive_manager.simulator.engine.effects.model;

import com.drive.drive_manager.simulator.engine.Amount;
import com.drive.drive_manager.simulator.engine.CardFilter;

/**
 * Which cards an action works with: every choice of cards of the game is described this way.
 *
 * @param zones  comma separated: hand, deck, tributeZone, discardPile, lifeStack, field, frontLine, backLine, materials and resources
 *               (of the effect's card), materials:subject and resources:subject (of the ally chosen before), allies (your creatures on the
 *               field), looked (the cards an effect is looking at), saved:x (what the memory has under x), event (the card of the triggering event)
 * @param owner  whose zones
 * @param filter which of their cards
 * @param min    fewest to pick: in a cost it must be met in full or the effect is not activated; at resolution as many as there are
 * @param max    most to pick
 * @param self   what happens with the effect's own card
 * @param mode   how they are decided
 */
public record Selector(String zones, Owner owner, CardFilter filter, Amount min, Amount max, Self self, Mode mode) {

    public enum Owner { YOU, OPPONENT, BOTH }

    public enum Self {
        ANY,
        /** "otra", "otras": never the effect's own card. */
        EXCLUDE,
        /** "incluida esta carta": it must be among those picked, and counts whatever the filter says. */
        REQUIRE
    }

    public enum Mode {
        /** The player picks. */
        CHOOSE,
        /** The first ones of the zones, in their order ("del tope de tu mazo"). */
        TOP,
        /** All that match. */
        ALL,
        /** The effect's own card, while it is in one of the zones. */
        SELF
    }

    public Selector {
        filter = filter == null ? CardFilter.any() : filter;
        self = self == null ? Self.ANY : self;
        mode = mode == null ? Mode.CHOOSE : mode;
    }

    /** Exactly {@code count} of yours, which the player picks. */
    public static Selector yours(String zones, CardFilter filter, int count) {
        return new Selector(zones, Owner.YOU, filter, new Amount.Fixed(count), new Amount.Fixed(count), Self.ANY, Mode.CHOOSE);
    }

    /** Exactly {@code count} enemies: the opponent's Línea frontal. */
    public static Selector enemies(CardFilter filter, int count) {
        return new Selector("frontLine", Owner.OPPONENT, filter, new Amount.Fixed(count), new Amount.Fixed(count), Self.ANY, Mode.CHOOSE);
    }

    /** All of yours that match. */
    public static Selector all(String zones, CardFilter filter) {
        return new Selector(zones, Owner.YOU, filter, new Amount.Fixed(0), new Amount.Fixed(Integer.MAX_VALUE), Self.ANY, Mode.ALL);
    }

    /** The effect's own card, while it is in one of these zones. */
    public static Selector self(String zones) {
        return new Selector(zones, Owner.YOU, CardFilter.any(), new Amount.Fixed(1), new Amount.Fixed(1), Self.ANY, Mode.SELF);
    }

    /** All that the memory has under that name, without asking. */
    public static Selector saved(String name) {
        return all("saved:" + name, CardFilter.any());
    }

    /** "hasta N": none is fine. */
    public Selector upTo() {
        return atLeast(0);
    }

    public Selector atLeast(int min) {
        return new Selector(zones, owner, filter, new Amount.Fixed(min), max, self, mode);
    }

    public Selector with(Self self) {
        return new Selector(zones, owner, filter, min, max, self, mode);
    }

    public Selector as(Mode mode) {
        return new Selector(zones, owner, filter, min, max, self, mode);
    }
}
