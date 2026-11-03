package com.drive.drive_manager.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * A player's cards and fragments. Every copy has a finish: plain, foil or prism (prismatic, rarer); a card comes out
 * foil by the same odds whether it is pulled from a pack or crafted. Breaking a copy gives fragments of its card's
 * rarity (10, a foil 30, a prismatic 90); 30 fragments of a rarity craft any card of that rarity.
 * A card with an alternative art (Card.altImage) can also come out of a pack with it, very rarely, and in any finish:
 * those copies are kept under keys of their own ("alt-" + finish) next to the others of the same card, so they count
 * as that card (decks, distinct cards) but are never broken, crafted or counted as extras. Pure.
 */
public final class CollectionRules {

    public static final List<String> FINISHES = List.of("plain", "foil", "prism");
    public static final double FOIL_ODDS = 0.05;
    public static final double PRISM_ODDS = 0.005;
    /** A pulled copy of a card with an alternative art comes out with it (1 in 10 000), whatever its finish. */
    public static final double ALT_ODDS = 0.0001;
    public static final String ALT_PREFIX = "alt-";
    /** The keys a deck takes its copies from, the finest first: the alternative art, then the finishes. */
    public static final List<String> COPY_ORDER = List.of("alt-prism", "alt-foil", "alt-plain", "prism", "foil", "plain");
    public static final int CRAFT_COST = 30;
    /** Copies a deck can use of a card: more than these of one printing are extra. */
    public static final int MAX_COPIES = 4;
    /**
     * Copies one request can craft (one card, a deck's missing cards, or crafting until a finish comes out): keeps the
     * fragment totals far from overflowing an int and the lists small. Enough to chase a prismatic (1 in 200).
     */
    public static final int MAX_CRAFTS = 1000;

    private CollectionRules() {
    }

    /** The finish a card comes out with. */
    public static String rollFinish(DoubleSupplier random) {
        double roll = random.getAsDouble();
        if (roll < PRISM_ODDS) return "prism";
        if (roll < PRISM_ODDS + FOIL_ODDS) return "foil";
        return "plain";
    }

    /** The key a copy is kept under: its finish, or "alt-" + finish with the alternative art. */
    public static String copyKey(String finish, boolean alt) {
        return alt ? ALT_PREFIX + finish : finish;
    }

    public static boolean isAlt(String key) {
        return key != null && key.startsWith(ALT_PREFIX);
    }

    /** The finish of a kept copy ("alt-foil" → "foil"). */
    public static String finishOf(String key) {
        return isAlt(key) ? key.substring(ALT_PREFIX.length()) : key;
    }

    /** Fragments `copies` copies cost to craft; never overflows (ArithmeticException instead). */
    public static int craftCost(int copies) {
        return Math.multiplyExact(copies, CRAFT_COST);
    }

    /** Fragments a broken copy gives. */
    public static int fragmentsOf(String finish) {
        return switch (finish) {
            case "prism" -> 90;
            case "foil" -> 30;
            default -> 10;
        };
    }

    public static boolean isFinish(String finish) {
        return FINISHES.contains(finish);
    }

    /** Rank of a finish: plain 0, foil 1, prism 2. */
    public static int rank(String finish) {
        return FINISHES.indexOf(finish);
    }

    /**
     * Crafting a card again and again until one comes out at least as fine as `target`, or the fragments (of its
     * rarity) run out, or MAX_CRAFTS copies were crafted: the finishes crafted, in order.
     */
    public static List<String> craftUntil(String target, int fragments, DoubleSupplier random) {
        List<String> crafted = new ArrayList<>();
        int left = fragments;
        while (left >= CRAFT_COST && crafted.size() < MAX_CRAFTS) {
            left -= CRAFT_COST;
            String finish = rollFinish(random);
            crafted.add(finish);
            if (rank(finish) >= rank(target)) break;
        }
        return crafted;
    }

    /**
     * The key of each of a deck's `count` copies of a printing, taken from the owned copies the finest first (the
     * alternative art before all, COPY_ORDER); a copy the player lacks is null.
     */
    public static List<String> copyFinishes(Map<String, Integer> owned, int count) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < COPY_ORDER.size() && out.size() < count; i++) {
            String finish = COPY_ORDER.get(i);
            int n = owned == null ? 0 : Math.max(0, owned.getOrDefault(finish, 0));
            for (int k = 0; k < n && out.size() < count; k++) out.add(finish);
        }
        while (out.size() < count) out.add(null);
        return out;
    }

    /**
     * The extra copies of one printing (beyond MAX_COPIES), by finish: the finest ones are kept, so the plain copies go
     * first, then the foil ones.
     */
    public static Map<String, Integer> extras(Map<String, Integer> owned) {
        int keep = MAX_COPIES;
        Map<String, Integer> extra = new LinkedHashMap<>();
        for (int i = FINISHES.size() - 1; i >= 0; i--) {
            String finish = FINISHES.get(i);
            int count = Math.max(0, owned.getOrDefault(finish, 0));
            int kept = Math.min(count, keep);
            keep -= kept;
            if (count - kept > 0) extra.put(finish, count - kept);
        }
        return extra;
    }
}
