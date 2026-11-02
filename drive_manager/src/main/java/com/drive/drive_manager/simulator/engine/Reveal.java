package com.drive.drive_manager.simulator.engine;

/**
 * Whether the history names the cards an effect moves (designer ruling 2026-11-02): from a hidden zone to another hidden zone,
 * only when the effect asked for some kind of card ("toma 1 carta Verde"), to show it complies; otherwise only when they were or
 * end up in sight. Hidden zones: the lives, the hand, the deck and the face-down cards of the Tributos or the Descarte.
 */
public final class Reveal {

    private Reveal() {}

    /** A card in this zone, face down or not, is out of sight. */
    static boolean hidden(String zone, boolean faceDown) {
        return faceDown || "hand".equals(zone) || "deck".equals(zone);
    }

    /** The moved cards are named. */
    static boolean named(boolean fromHidden, String toZone, boolean toFaceDown, boolean restricted) {
        return !fromHidden || !hidden(toZone, toFaceDown) || restricted;
    }
}
