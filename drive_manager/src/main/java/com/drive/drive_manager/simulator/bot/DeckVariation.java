package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.DeckLoader;
import com.drive.drive_manager.simulator.room.DeckRules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The bot's deck of the day: a small change of a system deck, so the bot does not always play the same 50 cards. Pure.
 * <ul>
 *   <li><b>Related cards:</b> the catalog given (the deck's color in its block) minus the deck's names and the cards the
 *       engine does not run, each with an affinity to the deck: classes it shares (weighted by the deck's copies), the
 *       deck's classes written in its text, the deck's special summon method, the deck's name family ("Primera Línea").
 *       Affinity says how much a card belongs to the deck's plan, not how good it is: nothing here ranks cards.</li>
 *   <li><b>Changes</b> ({@link #MAX_SWAPS}): a flexible entry leaves (any of them, alike) and a related card of the same
 *       role comes in with its copies (role: Iniciador, creature level, structure or utility), chosen at random weighted
 *       by affinity. <b>Copies</b> ({@link #MAX_MOVES}): one copy moves between two flexible entries of the same role.</li>
 *   <li><b>Fixed:</b> the Iniciadores, the structures and the entries with 4 copies (the deck's core); the curve (copies
 *       per role) and the size never change.</li>
 * </ul>
 * A result the deck rules refuse ({@link DeckRules#refusal}) is dropped and the deck is played as it is.
 */
public final class DeckVariation {

    /** Changes of a card for another, at most (one at least). */
    static final int MAX_SWAPS = 2;
    /** Copies moved between two entries, at most (none at least). */
    static final int MAX_MOVES = 2;
    /** Affinity a card needs to be a candidate. */
    static final double MIN_AFFINITY = 0.9;
    private static final List<String> FAMILIES = List.of("primera línea", "templari");

    private DeckVariation() {}

    /** The cards to play and what changed ("Benzo ×2 → Peleador Del Castillo"; empty when the deck is played as it is). */
    public record Result(List<CardSlot> cards, List<String> changes) {}

    /** One distinct card of the deck and its copies. */
    private static final class Entry {
        CardSlot card;
        int copies;
        final boolean fixed;
        boolean changed;

        Entry(CardSlot card, int copies) {
            this.card = card;
            this.copies = copies;
            this.fixed = card.isStarter() || "structure".equals(card.getCardType()) || copies >= DeckRules.MAX_COPIES;
        }
    }

    public static Result vary(String deckName, List<CardSlot> deck, List<CardSlot> catalog, Random chance) {
        Map<String, Entry> byId = new LinkedHashMap<>();
        for (CardSlot c : deck) {
            Entry e = byId.get(c.getCardId());
            if (e == null) byId.put(c.getCardId(), new Entry(c, 1));
            else e.copies++;
        }
        List<Entry> entries = new ArrayList<>(byId.values());
        // Entries made again with their copies counted (fixed is decided by them)
        entries.replaceAll(e -> new Entry(e.card, e.copies));
        Profile profile = Profile.of(entries);

        Set<String> names = new HashSet<>();
        entries.forEach(e -> names.add(name(e.card)));
        Map<String, CardSlot> related = new LinkedHashMap<>();
        Map<String, Double> affinity = new HashMap<>();
        for (CardSlot c : catalog) {
            if (c.getCardName() == null || c.getUnprogrammed() != null || names.contains(name(c)) || related.containsKey(name(c))) continue;
            double a = profile.affinity(c);
            if (a < MIN_AFFINITY) continue;
            related.put(name(c), c);
            affinity.put(name(c), a);
        }

        List<String> changes = new ArrayList<>();
        int swaps = 1 + chance.nextInt(MAX_SWAPS);
        for (int i = 0; i < swaps; i++) {
            List<Entry> out = new ArrayList<>(entries.stream().filter(e -> !e.fixed && !e.changed).toList());
            Collections.shuffle(out, chance);
            for (Entry e : out) {
                List<CardSlot> same = related.values().stream().filter(c -> role(c).equals(role(e.card)) && !names.contains(name(c))).toList();
                if (same.isEmpty()) continue;
                CardSlot in = weighted(same, affinity, chance);
                changes.add(e.card.getCardName() + " ×" + e.copies + " → " + in.getCardName());
                names.remove(name(e.card));
                names.add(name(in));
                e.card = in;
                e.changed = true;
                break;
            }
        }
        int moves = chance.nextInt(MAX_MOVES + 1);
        // An entry that gave a copy never takes one back, nor gives away one it took: no move undoes another
        Set<Entry> gave = new HashSet<>();
        Set<Entry> took = new HashSet<>();
        for (int i = 0; i < moves; i++) {
            List<Entry> from = new ArrayList<>(entries.stream().filter(e -> !e.fixed && e.copies > 1 && !took.contains(e)).toList());
            Collections.shuffle(from, chance);
            for (Entry a : from) {
                List<Entry> to = entries.stream()
                        .filter(b -> b != a && !b.fixed && !gave.contains(b) && b.copies < DeckRules.MAX_COPIES - 1 && role(b.card).equals(role(a.card))).toList();
                if (to.isEmpty()) continue;
                Entry b = to.get(chance.nextInt(to.size()));
                a.copies--;
                b.copies++;
                gave.add(a);
                took.add(b);
                changes.add(a.card.getCardName() + " " + (a.copies + 1) + "→" + a.copies + ", " + b.card.getCardName() + " " + (b.copies - 1) + "→" + b.copies);
                break;
            }
        }

        List<CardSlot> cards = new ArrayList<>();
        for (Entry e : entries) for (int k = 0; k < e.copies; k++) cards.add(e.card);
        cards = DeckLoader.freshCopy(cards);
        if (changes.isEmpty() || cards.size() != deck.size() || DeckRules.refusal(deckName, cards) != null)
            return new Result(deck, List.of());
        return new Result(cards, changes);
    }

    /** Iniciador, creature level ("N3"), structure or utility: a card only takes the place of one of its role. */
    static String role(CardSlot c) {
        if (c.isStarter()) return "INI";
        if ("creature".equals(c.getCardType())) return "N" + c.getLevel();
        return String.valueOf(c.getCardType());
    }

    private static String name(CardSlot c) {
        return c.getCardName() == null ? "" : c.getCardName().toLowerCase(Locale.ROOT).strip();
    }

    private static CardSlot weighted(List<CardSlot> cards, Map<String, Double> affinity, Random chance) {
        double total = cards.stream().mapToDouble(c -> affinity.get(name(c))).sum();
        double r = chance.nextDouble() * total;
        for (CardSlot c : cards) {
            r -= affinity.get(name(c));
            if (r <= 0) return c;
        }
        return cards.get(cards.size() - 1);
    }

    /** What the deck is about: its classes (share of its copies), its special summon method, its name families. */
    private record Profile(Map<String, Double> classes, String summon, Set<String> families) {

        static Profile of(List<Entry> entries) {
            Map<String, Double> classes = new HashMap<>();
            Map<String, Integer> summons = new HashMap<>();
            Map<String, Integer> families = new HashMap<>();
            double total = 0;
            for (Entry e : entries) {
                for (String k : list(e.card.getClasses())) {
                    classes.merge(k.toLowerCase(Locale.ROOT), (double) e.copies, Double::sum);
                    total += e.copies;
                }
                if (e.card.getSpecialSummonKind() != null) summons.merge(e.card.getSpecialSummonKind(), e.copies, Integer::sum);
                for (String f : FAMILIES) if (name(e.card).contains(f)) families.merge(f, e.copies, Integer::sum);
            }
            double sum = total == 0 ? 1 : total;
            classes.replaceAll((k, v) -> v / sum);
            String summon = summons.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            Set<String> fam = new HashSet<>();
            families.forEach((f, n) -> { if (n >= 6) fam.add(f); });
            return new Profile(classes, summon, fam);
        }

        double affinity(CardSlot c) {
            double a = 0;
            for (String k : list(c.getClasses())) a += classes.getOrDefault(k.toLowerCase(Locale.ROOT), 0.0) * 3;
            for (String k : list(c.getMentions())) a += classes.getOrDefault(k.toLowerCase(Locale.ROOT), 0.0) * 1.5;
            if (summon != null && summon.equals(c.getSpecialSummonKind())) a += 0.5;
            for (String f : families) if (name(c).contains(f)) a += 1;
            // The color's utilities and structures always fit a deck of the color
            if (!"creature".equals(c.getCardType())) a += 0.6;
            return a;
        }

        private static List<String> list(List<String> l) {
            return l == null ? List.of() : l;
        }
    }
}
