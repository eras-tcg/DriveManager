package com.drive.drive_manager.simulator.bot

import java.util.Locale
import java.util.regex.Pattern

/**
 * How the bot looks ahead and scores a table: named numbers (the knobs), each with its default. [with]
 * changes some by name ("turns=2,life=12"), which is how variants are described and tuned.
 *
 * How it looks: `turns` (times the turn passes before the table is scored), `samples` (games on
 * copies per move, the rival's hidden cards dealt again in each), `budgetMs` (time for one decision),
 * `maxCandidates` (moves tried at most); `sameDeals` on (≥ 0.5): the k-th try of every move is played on
 * the k-th deal, so moves are compared on equal luck; `halving` on: the tries are shared out by successive
 * halving (see [MoveTrials]) instead of `samples` for each move.
 *
 * What a side is worth ([TableScore.side]): `life` per life; `hand` per card in the hand plus
 * `zHand`, `zTribute`, `zDiscard` times the worth of each card there; on the field each creature's
 * worth, `guard` times the strength of an active one in the Línea frontal (it can block), minus `exposed`
 * times the worth of an exhausted one there (it can be attacked); `support` and `structure` times the
 * worth of utilities and structures; `uHand` per utility in the hand, less `uCopies` for each copy of one
 * already counted there; minus `uTribute` per utility in the Tributos, times `uDamp` for each copy of it
 * still in the hand, field or deck; `sHand` per structure in the hand and `sField` per one on the field (designer,
 * 2026-11-04: holding utilities, not their copies, and holding and playing structures, pay off later); minus
 * `lowDeck` with fewer than 4 cards in the deck. The table's score is
 * the bot's side minus `aggression` times the rival's, plus `gems` per gem it has, plus `vTop` per
 * creature of its own stronger than every enemy one (no block stops it, it wins any clash) minus `aggression`
 * times the same for the rival.
 *
 * What a card is worth ([CardWorth]): its cost, `vStr` per strength and `vStrSq` per strength
 * squared (one big creature over two small ones of the same total), `vKeyword` per ability,
 * `vLevel` per level, `vAttached` per attachment, and per effect of each kind: `eContinuous`,
 * `eActivated`, `eEvent`, `eSummon`, `ePlay`, `eAttack`, `eDestroy`,
 * `eInherited`. The defaults of the knobs added later are the ones that score as before.
 *
 * And biases of each card of its own, 0 when not set: how much more (or less) that very card is worth than its stats
 * and kinds of effects say, on the field (`bias:<card name>`), in the Tributos (`biasT:<card name>`) and in
 * the Descarte (`biasD:<card name>`). In the hand a card counts by `zHand` only.
 */
class BotSettings private constructor(values: Map<String, Double>, bias: Map<String, Double>) {

    private val values: Map<String, Double> = java.util.Collections.unmodifiableMap(LinkedHashMap(values))
    /** The cards' own biases, by prefix and name in lower case ("biasT:dirumy"). */
    private val bias: Map<String, Double> = java.util.Collections.unmodifiableMap(LinkedHashMap(bias))
    // Read for every table scored: kept apart from the map
    @JvmField internal val life: Double = values["life"]!!
    @JvmField internal val hand: Double = values["hand"]!!
    @JvmField internal val guard: Double = values["guard"]!!
    @JvmField internal val exposed: Double = values["exposed"]!!
    @JvmField internal val support: Double = values["support"]!!
    @JvmField internal val structure: Double = values["structure"]!!
    @JvmField internal val lowDeck: Double = values["lowDeck"]!!
    @JvmField internal val zHand: Double = values["zHand"]!!
    @JvmField internal val zTribute: Double = values["zTribute"]!!
    @JvmField internal val zDiscard: Double = values["zDiscard"]!!
    @JvmField internal val gems: Double = values["gems"]!!
    @JvmField internal val aggression: Double = values["aggression"]!!
    @JvmField internal val vStr: Double = values["vStr"]!!
    @JvmField internal val vKeyword: Double = values["vKeyword"]!!
    @JvmField internal val vLevel: Double = values["vLevel"]!!
    @JvmField internal val vAttached: Double = values["vAttached"]!!
    @JvmField internal val vStrSq: Double = values["vStrSq"]!!
    @JvmField internal val vTop: Double = values["vTop"]!!
    @JvmField internal val uHand: Double = values["uHand"]!!
    @JvmField internal val uCopies: Double = values["uCopies"]!!
    @JvmField internal val uTribute: Double = values["uTribute"]!!
    @JvmField internal val uDamp: Double = values["uDamp"]!!
    @JvmField internal val sHand: Double = values["sHand"]!!
    @JvmField internal val sField: Double = values["sField"]!!
    @JvmField internal val vLanes: Double = values["vLanes"]!!
    @JvmField internal val hCopies: Double = values["hCopies"]!!
    @JvmField internal val hDead: Double = values["hDead"]!!
    @JvmField internal val hDeadSpecial: Double = values["hDeadSpecial"]!!
    @JvmField internal val eContinuous: Double = values["eContinuous"]!!
    @JvmField internal val eActivated: Double = values["eActivated"]!!
    @JvmField internal val eEvent: Double = values["eEvent"]!!
    @JvmField internal val eSummon: Double = values["eSummon"]!!
    @JvmField internal val ePlay: Double = values["ePlay"]!!
    @JvmField internal val eAttack: Double = values["eAttack"]!!
    @JvmField internal val eDestroy: Double = values["eDestroy"]!!
    @JvmField internal val eInherited: Double = values["eInherited"]!!

    /** These settings with the ones named in the text changed: "turns=2,samples=3,life=12" (unknown names fail). */
    fun with(text: String?): BotSettings {
        val v: MutableMap<String, Double> = LinkedHashMap(values)
        val b: MutableMap<String, Double> = LinkedHashMap(bias)
        if (text != null && !text.all { Character.isWhitespace(it) }) {
            for (pair in COMMA.split(text)) {
                if (pair.all { Character.isWhitespace(it) }) continue
                val kv = EQUALS.split(pair.trim { it <= ' ' })
                val key = kv[0].trim { it <= ' ' }
                val prefix = biasPrefix(key)
                if (kv.size == 2 && prefix != null && key.length > prefix.length) {
                    b[prefix + cardKey(key.substring(prefix.length))] = java.lang.Double.parseDouble(kv[1].trim { it <= ' ' })
                    continue
                }
                if (kv.size != 2 || !v.containsKey(key)) throw IllegalArgumentException("Unknown setting: " + pair)
                v[key] = java.lang.Double.parseDouble(kv[1].trim { it <= ' ' })
            }
        }
        return BotSettings(v, b)
    }

    /** The knobs (without the cards' biases). */
    fun asMap(): Map<String, Double> {
        return values
    }

    /** The cards' own biases set, by prefix and name in lower case ("bias:dirumy", "biasT:dirumy"). */
    fun biases(): Map<String, Double> {
        return bias
    }

    /** Every knob with its value, in the order of the defaults (the cards' biases apart). */
    fun values(): Map<String, Double> {
        return values
    }

    /** A knob, or a card's bias ("bias:dirumy", "biasT:dirumy", "biasD:dirumy"). */
    fun get(name: String?): Double {
        val prefix = biasPrefix(name)
        if (prefix != null) return bias.getOrDefault(prefix + cardKey(name!!.substring(prefix.length)), 0.0)
        val v = values[name] ?: throw IllegalArgumentException("Unknown setting: " + name)
        return v
    }

    /** The card's own bias on the field (0 when not set). */
    fun bias(cardName: String?): Double {
        return bias(BIAS, cardName)
    }

    /** The card's own bias in the zone of that prefix (0 when not set). */
    fun bias(prefix: String?, cardName: String?): Double {
        if (bias.isEmpty() || cardName == null) return 0.0
        return bias.getOrDefault(prefix + cardKey(cardName), 0.0)
    }

    fun hasBiases(): Boolean {
        return !bias.isEmpty()
    }

    fun turns(): Int {
        return values["turns"]!!.toInt()
    }

    fun samples(): Int {
        return values["samples"]!!.toInt()
    }

    fun budgetMs(): Long {
        return values["budgetMs"]!!.toLong()
    }

    fun maxCandidates(): Int {
        return values["maxCandidates"]!!.toInt()
    }

    fun sameDeals(): Boolean {
        return values["sameDeals"]!! >= 0.5
    }

    fun halving(): Boolean {
        return values["halving"]!! >= 0.5
    }

    fun life(): Double {
        return life
    }

    override fun toString(): String {
        return values.toString() + (if (bias.isEmpty()) "" else " " + bias)
    }

    companion object {

        /** The prefixes of a card's own biases, on the field, in the Tributos and in the Descarte: "bias:dirumy=1.2" (any case). */
        const val BIAS = "bias:"
        const val BIAS_TRIBUTE = "biasT:"
        const val BIAS_DISCARD = "biasD:"
        private val BIASES: List<String> = java.util.List.of(BIAS, BIAS_TRIBUTE, BIAS_DISCARD)

        private val COMMA = Pattern.compile(",")
        private val EQUALS = Pattern.compile("=")

        private val DEFAULTS: Map<String, Double> = defaults()
        @JvmField
        val DEFAULT = BotSettings(DEFAULTS, java.util.Map.of())

        private fun defaults(): Map<String, Double> {
            val d: MutableMap<String, Double> = LinkedHashMap()
            d.put("turns", 1.0)
            d.put("samples", 2.0)
            d.put("budgetMs", 400.0)
            d.put("maxCandidates", 16.0)
            d.put("sameDeals", 0.0)
            d.put("halving", 0.0)
            d.put("life", 10.0)
            d.put("hand", 1.5)
            d.put("guard", 0.3)
            d.put("exposed", 0.4)
            d.put("support", 0.5)
            d.put("structure", 0.5)
            d.put("lowDeck", 8.0)
            d.put("handDecay", 0.0)
            d.put("hWait", 0.0)
            d.put("hPickPlay", 0.0)
            d.put("hPickWait", 0.7)
            d.put("zHandPlay", 0.0)
            d.put("searchHope", 0.0)
            d.put("hRank", 0.0)
            d.put("rCopy", 0.4)
            d.put("rType", 0.8)
            d.put("rPlay", 0.85)
            d.put("lifeMine", 0.0)
            d.put("cascade", 0.0)
            d.put("overExtendPow", 1.0)
            d.put("overExtendUnc", 0.0)
            d.put("seqPass", 0.0)
            d.put("leafPlay", 0.0)
            d.put("handLow", 0.0)
            d.put("handFloor", 3.0)
            d.put("attackFirst", 0.0)
            d.put("overExtendRival", 0.0)
            d.put("planKeep", 0.0)
            d.put("hNoDebt", 0.0)
            d.put("hAttackPick", 0.0)
            d.put("sUse", 0.0)
            d.put("frontRisk", 0.0)
            d.put("advanceMore", 0.0)
            d.put("lifeFoe", 0.0)
            d.put("riskLives", 0.0)
            d.put("riskAlive", 0.0)
            d.put("lifeCurveExp", 0.0)
            d.put("hPreyGuard", 0.0)
            d.put("innerRival", 0.0)
            d.put("hCombat", 0.0)
            d.put("combatCounter", 1.0)
            d.put("combatPerk", 2.0)
            d.put("deckClock", 0.0)
            d.put("riskSum", 0.0)
            d.put("vResource", 0.0)
            d.put("vLevelSq", 0.0)
            d.put("hReduce", 0.0)
            d.put("riskCuts", 0.0)
            d.put("lethalLives", 0.0)
            d.put("winFlat", 0.0)
            // HandRank; TableScore (lifeMine, overExtend); SequenceSearch (cascade, seqPass); LeafPlay
            d.put("zHand", 0.0)
            d.put("zTribute", 0.0)
            d.put("zDiscard", 0.0)
            d.put("gems", 0.0)
            d.put("aggression", 1.0)
            d.put("vStr", 0.6)
            d.put("vKeyword", 0.5)
            d.put("vLevel", 0.3)
            d.put("vAttached", 0.5)
            d.put("vStrSq", 0.0)
            d.put("vTop", 0.0)
            // Sequences of own moves (SequenceSearch): off at seqDepth 0
            d.put("seqDepth", 0.0)
            d.put("seqKeep", 4.0)
            d.put("seqTriggers", 0.0)
            d.put("seqRollout", 0.0)
            d.put("seqShare", 0.25)
            d.put("innerCands", 0.0)
            d.put("innerMs", 20.0)
            d.put("innerFinal", 0.0)
            d.put("firstTries", 1.0)
            d.put("rankCands", 0.0)
            for (k in java.util.List.of("uHand", "uCopies", "uTribute", "uDamp", "sHand", "sField", "vLanes", "hCopies", "hDead", "hDeadSpecial")) d.put(k, 0.0)
            // Gems paid below 0 go to the rival for their turn (shared Contador): minus gemDebt per gem below 0 when its turn ends
            d.put("gemDebt", 0.0)
            for (e in java.util.List.of("eContinuous", "eActivated", "eEvent", "eSummon", "ePlay", "eAttack", "eDestroy", "eInherited")) d.put(e, 0.0)
            // The heuristic bot's rules (HeuristicBot): their defaults are the rules as first written; flags are on at 0.5 or more
            d.put("hMullCost", 4.0)
            d.put("hMullKeep", 2.0)
            d.put("hAttackFirst", 1.0)
            d.put("hPreyMargin", 0.0)
            d.put("hDirectMargin", 0.0)
            d.put("hEvenTrade", 1.0)
            d.put("hHold", 0.0)
            d.put("hActivate", 1.0)
            d.put("hBlockEven", 1.0)
            d.put("hChumpLives", 0.0)
            d.put("hAdvanceMinStr", 0.0)
            d.put("hAssault", 0.0)
            d.put("hRespond", 1.0)
            d.put("hOptional", 1.0)
            d.put("hUseful", 0.0)
            d.put("hUsefulEnd", 0.0)
            d.put("hBlockBest", 0.0)
            d.put("lifeExp", 0.0)
            d.put("hStructFirst", 0.0)
            d.put("rollTriggers", 0.0)
            // hUseful: see Usefulness;
            // On: what goes from the hand to the Tributos is a creature first, utilities and structures last; off (the
            // default): the least valuable card. A stopgap until the table's value sees what the hand and the Tributos hold
            d.put("hTributeCreatures", 0.0)
            return d
        }

        /** The bias prefix the key starts with, or null. */
        @JvmStatic
        fun biasPrefix(key: String?): String? {
            for (p in BIASES) if (key!!.startsWith(p)) return p
            return null
        }

        @JvmStatic
        fun cardKey(cardName: String?): String {
            return cardName!!.trim { it <= ' ' }.lowercase(Locale.ROOT)
        }
    }
}
