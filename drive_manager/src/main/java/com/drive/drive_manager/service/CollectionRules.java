package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.AltArt;
import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.ShopOdds;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * A player's cards and fragments. Every copy has a finish: plain, foil or prism (prismatic, rarer); a card comes out
 * foil by the same odds whether it is pulled from a pack or crafted. Breaking a copy gives fragments of its card's
 * rarity (10, a foil 30, a prismatic 90); 30 fragments of a rarity craft any card of that rarity. The odds come from
 * the shop's (ShopOdds); the defaults stand in where none are given.
 * A card with alternative arts (Card.altArts) can also come out of a pack with one, very rarely, and in any finish:
 * those copies are kept under keys of their own ("alt-" + art id + "-" + finish; the single art of before, "alt-" +
 * finish) next to the others of the same card, so they count as that card (decks, distinct cards) but are never
 * broken, crafted or counted as extras. Pure.
 */
public final class CollectionRules {

    public static final List<String> FINISHES = List.of("plain", "foil", "prism");
    public static final String ALT_PREFIX = "alt-";
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

    /** The finish a card comes out with, by the default odds. */
    public static String rollFinish(DoubleSupplier random) {
        return rollFinish(random, ShopOdds.defaults());
    }

    /** The finish a card comes out with: prismatic, then foil, by the shop's odds. */
    public static String rollFinish(DoubleSupplier random, ShopOdds odds) {
        double roll = random.getAsDouble();
        if (roll < odds.getPrism()) return "prism";
        if (roll < odds.getPrism() + odds.getFoil()) return "foil";
        return "plain";
    }

    /**
     * The alternative art a pulled copy comes out with, or null (most times): one roll against the arts' odds one after
     * another (an art without its own takes `shopOdds`), so each comes out by its own chance.
     */
    public static String pickArt(List<AltArt> arts, double shopOdds, DoubleSupplier random) {
        if (arts == null || arts.isEmpty()) return null;
        double roll = random.getAsDouble();
        double cumulative = 0;
        for (AltArt art : arts) {
            cumulative += oddsOf(art, shopOdds);
            if (roll < cumulative) return art.getId();
        }
        return null;
    }

    /** An art's chance per pulled copy: its own, or the shop's. */
    public static double oddsOf(AltArt art, double shopOdds) {
        return art.getOdds() == null ? shopOdds : art.getOdds();
    }

    /** The key a copy is kept under: its finish, or "alt-" + art + "-" + finish with an alternative art. */
    public static String copyKey(String finish, String art) {
        if (art == null) return finish;
        return Card.LEGACY_ART_ID.equals(art) ? ALT_PREFIX + finish : ALT_PREFIX + art + "-" + finish;
    }

    public static boolean isAlt(String key) {
        return key != null && key.startsWith(ALT_PREFIX);
    }

    /** The finish of a kept copy ("alt-x1-foil" or "alt-foil" → "foil"). */
    public static String finishOf(String key) {
        if (!isAlt(key)) return key;
        return key.substring(key.lastIndexOf('-') + 1);
    }

    /** The alternative art of a kept copy ("alt-x1-foil" → "x1", "alt-foil" → the art of before), null for none. */
    public static String artOf(String key) {
        if (!isAlt(key)) return null;
        String rest = key.substring(ALT_PREFIX.length());
        int dash = rest.lastIndexOf('-');
        return dash < 0 ? Card.LEGACY_ART_ID : rest.substring(0, dash);
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
        return craftUntil(target, fragments, random, ShopOdds.defaults());
    }

    public static List<String> craftUntil(String target, int fragments, DoubleSupplier random, ShopOdds odds) {
        List<String> crafted = new ArrayList<>();
        int left = fragments;
        while (left >= CRAFT_COST && crafted.size() < MAX_CRAFTS) {
            left -= CRAFT_COST;
            String finish = rollFinish(random, odds);
            crafted.add(finish);
            if (rank(finish) >= rank(target)) break;
        }
        return crafted;
    }

    /**
     * The key of each of a deck's `count` copies of a printing, taken from the owned copies the finest first (the
     * alternative arts before all, the finest finish first); a copy the player lacks is null.
     */
    public static List<String> copyFinishes(Map<String, Integer> owned, int count) {
        List<String> out = new ArrayList<>();
        List<String> order = copyOrder(owned);
        for (int i = 0; i < order.size() && out.size() < count; i++) {
            String finish = order.get(i);
            int n = owned == null ? 0 : Math.max(0, owned.getOrDefault(finish, 0));
            for (int k = 0; k < n && out.size() < count; k++) out.add(finish);
        }
        while (out.size() < count) out.add(null);
        return out;
    }

    /** The keys a deck takes its copies from: the alternative arts (finest finish first, then by art), then the finishes. */
    static List<String> copyOrder(Map<String, Integer> owned) {
        List<String> alts = owned == null ? new ArrayList<>() : new ArrayList<>(owned.keySet().stream().filter(CollectionRules::isAlt).toList());
        alts.sort(Comparator.comparingInt((String k) -> -rank(finishOf(k))).thenComparing(Comparator.naturalOrder()));
        List<String> order = new ArrayList<>(alts);
        for (int i = FINISHES.size() - 1; i >= 0; i--) order.add(FINISHES.get(i));
        return order;
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
