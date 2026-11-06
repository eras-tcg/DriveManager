package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.EffectEngine;
import com.drive.drive_manager.simulator.engine.LegalActions;
import com.drive.drive_manager.simulator.engine.Prompt;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How the bot looks ahead and scores a table: named numbers (the knobs), each with its default. {@link #with}
 * changes some by name ("turns=2,life=12"), which is how variants are described and tuned.
 * <p>
 * How it looks: {@code turns} (times the turn passes before the table is scored), {@code samples} (games on
 * copies per move, the rival's hidden cards dealt again in each), {@code budgetMs} (time for one decision),
 * {@code maxCandidates} (moves tried at most); {@code sameDeals} on (≥ 0.5): the k-th try of every move is played on
 * the k-th deal, so moves are compared on equal luck; {@code halving} on: the tries are shared out by successive
 * halving (see {@link MoveTrials}) instead of {@code samples} for each move.
 * <p>
 * What a side is worth ({@link TableScore#side}): {@code life} per life; {@code hand} per card in the hand plus
 * {@code zHand}, {@code zTribute}, {@code zDiscard} times the worth of each card there; on the field each creature's
 * worth, {@code guard} times the strength of an active one in the Línea frontal (it can block), minus {@code exposed}
 * times the worth of an exhausted one there (it can be attacked); {@code support} and {@code structure} times the
 * worth of utilities and structures; {@code uHand} per utility in the hand, less {@code uCopies} for each copy of one
 * already counted there; minus {@code uTribute} per utility in the Tributos, times {@code uDamp} for each copy of it
 * still in the hand, field or deck; {@code sHand} per structure in the hand and {@code sField} per one on the field (designer,
 * 2026-11-04: holding utilities, not their copies, and holding and playing structures, pay off later); minus
 * {@code lowDeck} with fewer than 4 cards in the deck. The table's score is
 * the bot's side minus {@code aggression} times the rival's, plus {@code gems} per gem it has, plus {@code vTop} per
 * creature of its own stronger than every enemy one (no block stops it, it wins any clash) minus {@code aggression}
 * times the same for the rival.
 * <p>
 * What a card is worth ({@link CardWorth}): its cost, {@code vStr} per strength and {@code vStrSq} per strength
 * squared (one big creature over two small ones of the same total), {@code vKeyword} per ability,
 * {@code vLevel} per level, {@code vAttached} per attachment, and per effect of each kind: {@code eContinuous},
 * {@code eActivated}, {@code eEvent}, {@code eSummon}, {@code ePlay}, {@code eAttack}, {@code eDestroy},
 * {@code eInherited}. The defaults of the knobs added later are the ones that score as before.
 * <p>
 * And biases of each card of its own, 0 when not set: how much more (or less) that very card is worth than its stats
 * and kinds of effects say, on the field ({@code bias:<card name>}), in the Tributos ({@code biasT:<card name>}) and in
 * the Descarte ({@code biasD:<card name>}). In the hand a card counts by {@code zHand} only.
 */
public final class BotSettings {

    /** The prefixes of a card's own biases, on the field, in the Tributos and in the Descarte: "bias:dirumy=1.2" (any case). */
    public static final String BIAS = "bias:";
    public static final String BIAS_TRIBUTE = "biasT:";
    public static final String BIAS_DISCARD = "biasD:";
    private static final List<String> BIASES = List.of(BIAS, BIAS_TRIBUTE, BIAS_DISCARD);

    private static final Map<String, Double> DEFAULTS = defaults();
    public static final BotSettings DEFAULT = new BotSettings(DEFAULTS, Map.of());

    private final Map<String, Double> values;
    /** The cards' own biases, by prefix and name in lower case ("biasT:dirumy"). */
    private final Map<String, Double> bias;
    // Read for every table scored: kept apart from the map
    final double life, hand, guard, exposed, support, structure, lowDeck, zHand, zTribute, zDiscard, gems, aggression;
    final double vStr, vKeyword, vLevel, vAttached, vStrSq, vTop, uHand, uCopies, uTribute, uDamp, sHand, sField, vLanes, hCopies, hDead, hDeadSpecial;
    final double eContinuous, eActivated, eEvent, eSummon, ePlay, eAttack, eDestroy, eInherited;

    private BotSettings(Map<String, Double> values, Map<String, Double> bias) {
        this.values = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
        this.bias = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(bias));
        life = values.get("life");
        hand = values.get("hand");
        guard = values.get("guard");
        exposed = values.get("exposed");
        support = values.get("support");
        structure = values.get("structure");
        lowDeck = values.get("lowDeck");
        zHand = values.get("zHand");
        zTribute = values.get("zTribute");
        zDiscard = values.get("zDiscard");
        gems = values.get("gems");
        aggression = values.get("aggression");
        vStr = values.get("vStr");
        vKeyword = values.get("vKeyword");
        vLevel = values.get("vLevel");
        vAttached = values.get("vAttached");
        vStrSq = values.get("vStrSq");
        vTop = values.get("vTop");
        uHand = values.get("uHand"); uCopies = values.get("uCopies"); uTribute = values.get("uTribute"); uDamp = values.get("uDamp"); sHand = values.get("sHand"); sField = values.get("sField"); vLanes = values.get("vLanes"); hCopies = values.get("hCopies"); hDead = values.get("hDead"); hDeadSpecial = values.get("hDeadSpecial");
        eContinuous = values.get("eContinuous");
        eActivated = values.get("eActivated");
        eEvent = values.get("eEvent");
        eSummon = values.get("eSummon");
        ePlay = values.get("ePlay");
        eAttack = values.get("eAttack");
        eDestroy = values.get("eDestroy");
        eInherited = values.get("eInherited");
    }

    private static Map<String, Double> defaults() {
        Map<String, Double> d = new LinkedHashMap<>();
        d.put("turns", 1.0);
        d.put("samples", 2.0);
        d.put("budgetMs", 400.0);
        d.put("maxCandidates", 16.0);
        d.put("sameDeals", 0.0);
        d.put("halving", 0.0);
        d.put("life", 10.0);
        d.put("hand", 1.5);
        d.put("guard", 0.3);
        d.put("exposed", 0.4);
        d.put("support", 0.5);
        d.put("structure", 0.5);
        d.put("lowDeck", 8.0);
        d.put("handDecay", 0.0);
        d.put("hWait", 0.0);
        d.put("hPickPlay", 0.0);
        d.put("hPickWait", 0.7);
        d.put("zHandPlay", 0.0);
        d.put("searchHope", 0.0);
        d.put("hRank", 0.0);
        d.put("rCopy", 0.4);
        d.put("rType", 0.8);
        d.put("rPlay", 0.85);
        d.put("lifeMine", 0.0);
        d.put("cascade", 0.0);
        d.put("overExtendPow", 1.0);
        d.put("overExtendUnc", 0.0);
        d.put("seqPass", 0.0);
        d.put("leafPlay", 0.0);
        d.put("handLow", 0.0);
        d.put("handFloor", 3.0);
        d.put("attackFirst", 0.0);
        d.put("overExtendRival", 0.0);
        d.put("planKeep", 0.0);
        d.put("hNoDebt", 0.0);
        d.put("hAttackPick", 0.0);
        d.put("sUse", 0.0);
        d.put("frontRisk", 0.0);
        d.put("advanceMore", 0.0);
        d.put("lifeFoe", 0.0);
        d.put("riskLives", 0.0);
        d.put("riskAlive", 0.0);
        d.put("lifeCurveExp", 0.0);
        d.put("hPreyGuard", 0.0);
        d.put("innerRival", 0.0);
        d.put("hCombat", 0.0);
        d.put("combatCounter", 1.0);
        d.put("combatPerk", 2.0);
        d.put("deckClock", 0.0);
        d.put("riskSum", 0.0);
        d.put("vResource", 0.0);
        d.put("vLevelSq", 0.0);
        d.put("hReduce", 0.0);
        d.put("riskCuts", 0.0);
        d.put("lethalLives", 0.0);
        d.put("winFlat", 0.0);
        // HandRank; TableScore (lifeMine, overExtend); SequenceSearch (cascade, seqPass); LeafPlay
        d.put("zHand", 0.0);
        d.put("zTribute", 0.0);
        d.put("zDiscard", 0.0);
        d.put("gems", 0.0);
        d.put("aggression", 1.0);
        d.put("vStr", 0.6);
        d.put("vKeyword", 0.5);
        d.put("vLevel", 0.3);
        d.put("vAttached", 0.5);
        d.put("vStrSq", 0.0);
        d.put("vTop", 0.0);
        // Sequences of own moves (SequenceSearch): off at seqDepth 0
        d.put("seqDepth", 0.0);
        d.put("seqKeep", 4.0);
        d.put("seqTriggers", 0.0);
        d.put("seqRollout", 0.0);
        d.put("seqShare", 0.25);
        d.put("innerCands", 0.0);
        d.put("innerMs", 20.0);
        d.put("innerFinal", 0.0);
        d.put("firstTries", 1.0);
        d.put("rankCands", 0.0);
        for (String k : List.of("uHand", "uCopies", "uTribute", "uDamp", "sHand", "sField", "vLanes", "hCopies", "hDead", "hDeadSpecial")) d.put(k, 0.0);
        // Gems paid below 0 go to the rival for their turn (shared Contador): minus gemDebt per gem below 0 when its turn ends
        d.put("gemDebt", 0.0);
        for (String e : List.of("eContinuous", "eActivated", "eEvent", "eSummon", "ePlay", "eAttack", "eDestroy", "eInherited")) d.put(e, 0.0);
        // The heuristic bot's rules (HeuristicBot): their defaults are the rules as first written; flags are on at 0.5 or more
        d.put("hMullCost", 4.0);
        d.put("hMullKeep", 2.0);
        d.put("hAttackFirst", 1.0);
        d.put("hPreyMargin", 0.0);
        d.put("hDirectMargin", 0.0);
        d.put("hEvenTrade", 1.0);
        d.put("hHold", 0.0);
        d.put("hActivate", 1.0);
        d.put("hBlockEven", 1.0);
        d.put("hChumpLives", 0.0);
        d.put("hAdvanceMinStr", 0.0);
        d.put("hAssault", 0.0);
        d.put("hRespond", 1.0);
        d.put("hOptional", 1.0);
        d.put("hUseful", 0.0);
        d.put("hUsefulEnd", 0.0);
        d.put("hBlockBest", 0.0);
        d.put("lifeExp", 0.0);
        d.put("hStructFirst", 0.0);
        d.put("rollTriggers", 0.0);
        // hUseful: see Usefulness;
        // On: what goes from the hand to the Tributos is a creature first, utilities and structures last; off (the
        // default): the least valuable card. A stopgap until the table's value sees what the hand and the Tributos hold
        d.put("hTributeCreatures", 0.0);
        return d;
    }

    /** These settings with the ones named in the text changed: "turns=2,samples=3,life=12" (unknown names fail). */
    public BotSettings with(String text) {
        Map<String, Double> v = new LinkedHashMap<>(values);
        Map<String, Double> b = new LinkedHashMap<>(bias);
        if (text != null && !text.isBlank()) {
            for (String pair : text.split(",")) {
                if (pair.isBlank()) continue;
                String[] kv = pair.trim().split("=");
                String key = kv[0].trim();
                String prefix = biasPrefix(key);
                if (kv.length == 2 && prefix != null && key.length() > prefix.length()) {
                    b.put(prefix + cardKey(key.substring(prefix.length())), Double.parseDouble(kv[1].trim()));
                    continue;
                }
                if (kv.length != 2 || !v.containsKey(key)) throw new IllegalArgumentException("Unknown setting: " + pair);
                v.put(key, Double.parseDouble(kv[1].trim()));
            }
        }
        return new BotSettings(v, b);
    }

    /** The knobs (without the cards' biases). */
    public Map<String, Double> asMap() {
        return values;
    }

    /** The cards' own biases set, by prefix and name in lower case ("bias:dirumy", "biasT:dirumy"). */
    public Map<String, Double> biases() {
        return bias;
    }

    /** The bias prefix the key starts with, or null. */
    public static String biasPrefix(String key) {
        for (String p : BIASES) if (key.startsWith(p)) return p;
        return null;
    }

    /** Every knob with its value, in the order of the defaults (the cards' biases apart). */
    public Map<String, Double> values() { return values;
    };

    /** A knob, or a card's bias ("bias:dirumy", "biasT:dirumy", "biasD:dirumy"). */
    public double get(String name) {
        String prefix = biasPrefix(name);
        if (prefix != null) return bias.getOrDefault(prefix + cardKey(name.substring(prefix.length())), 0.0);
        Double v = values.get(name);
        if (v == null) throw new IllegalArgumentException("Unknown setting: " + name);
        return v;
    }

    /** The card's own bias on the field (0 when not set). */
    public double bias(String cardName) {
        return bias(BIAS, cardName);
    }

    /** The card's own bias in the zone of that prefix (0 when not set). */
    public double bias(String prefix, String cardName) {
        if (bias.isEmpty() || cardName == null) return 0;
        return bias.getOrDefault(prefix + cardKey(cardName), 0.0);
    }

    boolean hasBiases() {
        return !bias.isEmpty();
    }

    static String cardKey(String cardName) {
        return cardName.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public int turns() {
        return values.get("turns").intValue();
    }

    public int samples() {
        return values.get("samples").intValue();
    }

    public long budgetMs() {
        return values.get("budgetMs").longValue();
    }

    public int maxCandidates() {
        return values.get("maxCandidates").intValue();
    }

    public boolean sameDeals() {
        return values.get("sameDeals") >= 0.5;
    }

    public boolean halving() {
        return values.get("halving") >= 0.5;
    }
    public double life() {
        return life;
    }

    @Override
    public String toString() {
        return values + (bias.isEmpty() ? "" : " " + bias);
    }
}
