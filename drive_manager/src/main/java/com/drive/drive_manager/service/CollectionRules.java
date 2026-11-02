package com.drive.drive_manager.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * A player's cards and fragments. Every copy has a finish: plain, foil or prism (prismatic, rarer); a card comes out
 * foil by the same odds whether it is pulled from a pack or crafted. Breaking a copy gives fragments of its card's
 * rarity (10, a foil 30, a prismatic 90); 30 fragments of a rarity craft any card of that rarity. Pure.
 */
public final class CollectionRules {

    public static final List<String> FINISHES = List.of("plain", "foil", "prism");
    public static final double FOIL_ODDS = 0.05;
    public static final double PRISM_ODDS = 0.005;
    public static final int CRAFT_COST = 30;
    /** Copies a deck can use of a card: more than these of one printing are extra. */
    public static final int MAX_COPIES = 4;

    private CollectionRules() {
    }

    /** The finish a card comes out with. */
    public static String rollFinish(DoubleSupplier random) {
        double roll = random.getAsDouble();
        if (roll < PRISM_ODDS) return "prism";
        if (roll < PRISM_ODDS + FOIL_ODDS) return "foil";
        return "plain";
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
     * rarity) run out: the finishes crafted, in order.
     */
    public static List<String> craftUntil(String target, int fragments, DoubleSupplier random) {
        List<String> crafted = new ArrayList<>();
        int left = fragments;
        while (left >= CRAFT_COST) {
            left -= CRAFT_COST;
            String finish = rollFinish(random);
            crafted.add(finish);
            if (rank(finish) >= rank(target)) break;
        }
        return crafted;
    }

    /**
     * The finish of each of a deck's `count` copies of a printing, taken from the owned copies the finest first; a copy
     * the player lacks is null.
     */
    public static List<String> copyFinishes(Map<String, Integer> owned, int count) {
        List<String> out = new ArrayList<>();
        for (int i = FINISHES.size() - 1; i >= 0 && out.size() < count; i--) {
            String finish = FINISHES.get(i);
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
