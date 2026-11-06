package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.CardEffects;
import com.drive.drive_manager.simulator.engine.EffectEngine;
import com.drive.drive_manager.simulator.engine.EffectScript;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * frontRisk k (the user's call, 2026-11-05): a creature in the Línea frontal is where the rival's removal reaches (every
 * destruction effect of the starter decks targets the Línea frontal only); the Retaguardia is safe. The tries see a
 * removal only when the deal gives the rival one and it plays it, too noisy for a chance. So the table pays k times the
 * expected loss of the creature of its own most at risk there: for each one, the chance that the rival holds or draws
 * (its hand and one more card, from its hand and deck: what the bot does not see) a destruction it can pay next turn
 * that reaches it (by the strength and level the effect's text allows), times what the creature is worth.
 */
final class RemovalRisk {

    /** What a destruction effect reaches: strength and level at most, or a level exactly (-1: no limit). */
    private record Reach(int cost, int maxStrength, int maxLevel, int level) {
        boolean hits(CardSlot c) {
            int str = EffectEngine.strength(c), lvl = c.getLevel() == null ? 0 : c.getLevel();
            return (maxStrength < 0 || str <= maxStrength) && (maxLevel < 0 || lvl <= maxLevel) && (level < 0 || lvl == level);
        }
    }

    private static final Pattern STRENGTH = Pattern.compile("fuerza (\\d+) o menos");
    private static final Pattern LEVEL_MAX = Pattern.compile("nivel (\\d+) o menor");
    private static final Pattern LEVEL = Pattern.compile("nivel (\\d+)(?! o)");
    /** The destructions of each card, by name (none: not a removal). */
    private static final Map<String, List<Reach>> BY_CARD = new ConcurrentHashMap<>();
    /**
     * riskCuts (the user's ST1 games, 2026-11-05): a creature reduced to 0 is destroyed, so a strength reduction of N is a
     * removal of every creature of strength N or less (Arc-xioma, Mathaus, Bertalum; Hez all of the Línea frontal at once).
     * The cuts of each card, by name; and the rival's creatures on the field that cut on their own (an activated effect,
     * one at the start of its turn) threaten for sure next turn, when they have the materials it asks to tribute.
     */
    private static final Map<String, List<Reach>> CUTS_BY_CARD = new ConcurrentHashMap<>();
    private static final Pattern CUT = Pattern.compile("(?:reduce|reducir)[^.;]*?fuerza en (\\d+)|(?:reduce|reducir) en (\\d+) su fuerza");
    private static final Pattern MATERIALS = Pattern.compile("tributar (\\d+) materiales?");

    private RemovalRisk() {}

    /** The penalty each creature of its own in the Línea frontal carries when the rival's turn begins, by instanceId. */
    static Map<String, Double> risks(GameRoom room, PlayerState mine, PlayerState rival, BotSettings w) {
        Map<String, Double> out = new java.util.HashMap<>();
        double k = w.get("frontRisk");
        if (k == 0 || mine == null || rival == null) return out;
        List<CardSlot> pool = new ArrayList<>(rival.getHand());
        pool.addAll(rival.getDeck());
        if (pool.isEmpty()) return out;
        int draws = rival.getHand().size() + 1;
        // The gems the rival will have: its Marcador next turn, one more paid below 0 at most
        int gems = Math.min(5, rival.getResourceCount() + 1) + 1;
        // riskLives L (the user's call, 2026-11-05): with few lives of its own the bot must take risks (a creature kept
        // back lets the hits through), so the penalty is times 1 - e^(-lives / L): about 0.4 with 1 life, 0.6 with 2 (L=2)
        double lives = w.get("riskLives");
        double frail = lives > 0 ? 1 - Math.exp(-mine.getLifeStack().size() / lives) : 1;
        // riskCuts c: the cuts count c times what a destruction does (they often ask materials or a cost: 0.5 at half)
        double cutWeight = Math.max(0, Math.min(1, w.get("riskCuts")));
        boolean cuts = cutWeight > 0;
        // The cuts the rival's creatures on the field make next turn (riskCuts)
        List<Reach> sure = new ArrayList<>();
        if (cuts) for (CardSlot r : rival.getField()) if ("creature".equals(r.getCardType())) sure.addAll(fieldCuts(r));
        for (CardSlot c : mine.getField()) {
            if (!"creature".equals(c.getCardType()) || !EffectEngine.inFrontLine(c)) continue;
            long reaching = pool.stream().filter(r -> reaches(r, c, gems)).count();
            double p = reaching == 0 ? 0 : 1 - Math.pow(1 - (double) reaching / pool.size(), draws);
            if (cuts) {
                long cutting = pool.stream().filter(r -> !reaches(r, c, gems) && cuts(r, c, gems)).count();
                double pc = cutting == 0 ? 0 : 1 - Math.pow(1 - (double) cutting / pool.size(), draws);
                if (sure.stream().anyMatch(r -> r.hits(c))) pc = 1;
                p = 1 - (1 - p) * (1 - cutWeight * pc);
            }
            if (p <= 0) continue;
            out.put(c.getInstanceId(), k * frail * p * CardWorth.of(c, w));
        }
        return out;
    }

    /**
     * The penalty of the worst exposed one. riskAlive (the user's call, 2026-11-05): the try goes on through the rival's
     * turn, and a creature it destroyed there (an attack on it exhausted, a removal dealt) is already lost on the table:
     * charging its risk too counted it twice, and throwing it away in a useless attack looked as good as a hit on the
     * lives (Armadura De Plata against Krokler). So only those still on its field at the end of the try (alive) pay.
     */
    static double charge(Map<String, Double> risks, PlayerState mine, BotSettings w) {
        if (risks == null || risks.isEmpty()) return 0;
        boolean alive = mine != null && w.get("riskAlive") >= 0.5;
        List<Double> each = new ArrayList<>();
        for (Map.Entry<String, Double> e : risks.entrySet()) {
            if (alive && mine.getField().stream().noneMatch(c -> e.getKey().equals(c.getInstanceId()))) continue;
            each.add(e.getValue());
        }
        // riskSum n (the user's st2 games, 2026-11-05): the rival may play more than one removal (the mirror's Ddraig,
        // Fadrake, Llamarada…), so the n creatures most at risk pay, not only the worst: several bodies within the reach
        // of the removals cost more than one tower beyond it (0 or 1: the worst only, as before)
        each.sort(java.util.Comparator.reverseOrder());
        int n = Math.max(1, (int) Math.round(w.get("riskSum")));
        double sum = 0;
        for (int i = 0; i < Math.min(n, each.size()); i++) sum += each.get(i);
        return sum;
    }

    private static boolean reaches(CardSlot removal, CardSlot target, int gems) {
        if (removal.getCost() == null || removal.getCost() > gems) return false;
        return reaches(removal).stream().anyMatch(r -> r.hits(target));
    }

    private static List<Reach> reaches(CardSlot card) {
        String key = card.getCardName() == null ? "" : BotSettings.cardKey(card.getCardName());
        return BY_CARD.computeIfAbsent(key, k -> {
            List<Reach> out = new ArrayList<>();
            for (EffectScript e : CardEffects.of(card)) {
                String t = e.text() == null ? "" : e.text().toLowerCase(Locale.ROOT);
                if (!t.contains("destrú") || !t.contains("enemigo")) continue;
                out.add(new Reach(card.getCost() == null ? 0 : card.getCost(), first(STRENGTH, t), first(LEVEL_MAX, t),
                        LEVEL_MAX.matcher(t).find() ? -1 : first(LEVEL, t)));
            }
            return out;
        });
    }

    private static boolean cuts(CardSlot card, CardSlot target, int gems) {
        if (card.getCost() == null || card.getCost() > gems) return false;
        return cutsOf(card).stream().anyMatch(r -> r.hits(target));
    }

    /** The strength reductions on enemies a card's effects make: each reaches strength N or less. */
    private static List<Reach> cutsOf(CardSlot card) {
        String key = card.getCardName() == null ? "" : BotSettings.cardKey(card.getCardName());
        return CUTS_BY_CARD.computeIfAbsent(key, k -> {
            List<Reach> out = new ArrayList<>();
            for (EffectScript e : CardEffects.of(card)) {
                String t = e.text() == null ? "" : e.text().toLowerCase(Locale.ROOT);
                if (!t.contains("enemigo")) continue;
                Matcher m = CUT.matcher(t);
                if (m.find()) out.add(new Reach(card.getCost() == null ? 0 : card.getCost(), Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2)), -1, -1));
            }
            return out;
        });
    }

    /** The cuts a creature on the field makes by itself next turn (activated, or at the start of the turn), if it can pay. */
    private static List<Reach> fieldCuts(CardSlot creature) {
        List<Reach> out = new ArrayList<>();
        for (EffectScript e : CardEffects.of(creature)) {
            String t = e.text() == null ? "" : e.text().toLowerCase(Locale.ROOT);
            boolean own = e.moment() == EffectScript.Moment.ACTIVATED || t.contains("al inicio del turno");
            if (!own || !t.contains("enemigo")) continue;
            Matcher m = CUT.matcher(t);
            if (!m.find()) continue;
            int need = first(MATERIALS, t);
            if (need > 0 && creature.getMaterials().size() < need) continue;
            out.add(new Reach(0, Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2)), -1, -1));
        }
        return out;
    }

    private static int first(Pattern p, String t) {
        Matcher m = p.matcher(t);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }
}
