package com.drive.drive_manager.simulator.bot

import com.drive.drive_manager.simulator.engine.CardEffects
import com.drive.drive_manager.simulator.engine.EffectEngine
import com.drive.drive_manager.simulator.engine.LegalActions
import com.drive.drive_manager.simulator.engine.Playability
import com.drive.drive_manager.simulator.engine.Prompt
import com.drive.drive_manager.simulator.engine.PromptPurpose
import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState
import com.drive.drive_manager.simulator.room.Zone
import java.util.Locale
import java.util.regex.Pattern

/**
 * The bot that plays to win (phase 1), by simple rules over what it sees, with no look ahead:
 * - its turn: attacks first (an attack needs 0 or more gems, and summoning spends them), with the attacks it
 *   wins; then Rituals, summons and plays, the most valuable first; then its slow effects, once each; then it ends the turn;
 * - blocking: with a creature that wins the clash, trading an equal one for a better attacker, or anything when
 *   the damage would take its last lives;
 * - choices: enemies, the most valuable; its own cards for a cost or a loss, the least valuable; for a gain, the
 *   most valuable. It activates its optional effects, moves everything up and responds to the rival's effects.
 *
 * The thresholds of those rules are knobs of its [BotSettings] (the "h…" ones, their defaults the rules
 * above), so they can be tuned like the table's weights: they make its big decisions, and the look-ahead bot's
 * simulations play by them.
 */
open class HeuristicBot(rules: BotSettings) : BotPlayer() {

    /** Cards whose slow effect it activated this turn: once each, so the bot never repeats one forever. */
    private val activated: MutableSet<String?> = HashSet()
    /** The thresholds of its rules. */
    protected val rules: BotSettings = rules

    constructor() : this(BotSettings.DEFAULT)

    protected open fun on(flag: String): Boolean {
        return rules.get(flag) >= 0.5
    }

    // ------------------------------------------------------------------ Mulligan

    /** Sends to the bottom the cards it could not pay soon (cost hMullCost or more), keeping at least hMullKeep of the five. */
    override fun mulligan(room: GameRoom, self: PlayerState, top: List<CardSlot>): List<String> {
        val bottom: MutableList<String?> = ArrayList()
        val cost = rules.get("hMullCost")
        val keep = Math.round(rules.get("hMullKeep")).toInt()
        top.sortedWith(Comparator.comparingDouble { c: CardSlot -> if (c.cost == null) 0.0 else c.cost!!.toDouble() }.reversed())
            .filter { c -> c.cost != null && c.cost!! >= cost }
            .take(Math.max(0, top.size - keep))
            .forEach { c -> bottom.add(c.instanceId) }
        @Suppress("UNCHECKED_CAST")
        return bottom as List<String>
    }

    // ------------------------------------------------------------------ Its turn

    override fun mainMove(room: GameRoom, me: String?): GameAction? {
        val legal = LegalActions.of(room, me)
        @Suppress("UNCHECKED_CAST")
        val legalOf = legal as Map<String?, List<String>>
        val self = room.getPlayer(me)
        val rival = room.getPlayer(room.getOpponentId(me))

        val attackFirst = on("hAttackFirst")
        if (attackFirst) {
            val attack = bestAttack(room, me, self, rival, legal)
            if (attack != null) return attack
        }

        // Rituals, summons and plays: the most valuable first, keeping hHold cards in the hand
        val fromHand: MutableList<GameAction> = ArrayList()
        val hold = self!!.hand.size <= Math.round(rules.get("hHold"))
        val hand: MutableList<CardSlot> = ArrayList(self.hand)
        hand.sortWith(Comparator.comparingDouble { c: CardSlot -> value(c) }.reversed())
        // hStructFirst: structures before the Rituals and summons (their effects pay off on the invocations that follow)
        if (on("hStructFirst")) for (card in hand) {
            if ("structure" == card.cardType && legalOf.getOrDefault(card.instanceId, java.util.List.of()).contains("play"))
                fromHand.add(action(ActionType.PLAY_CARD, java.util.Map.of<String, Any?>("instanceId", card.instanceId, "x", summonX(self), "y", 75.0)))
        }
        for (id in legal.keys) {
            if (legal[id]!!.contains("ritual")) fromHand.add(action(ActionType.RITUAL_SUMMON, java.util.Map.of<String, Any?>("instanceId", id)))
        }
        val x = summonX(self)
        for (card in hand) {
            val can = legalOf.getOrDefault(card.instanceId, java.util.List.of())
            if (can.contains("summon")) fromHand.add(action(ActionType.NORMAL_SUMMON, java.util.Map.of<String, Any?>("instanceId", card.instanceId, "x", x, "y", 75.0)))
            if (can.contains("play")) fromHand.add(action(ActionType.PLAY_CARD, java.util.Map.of<String, Any?>("instanceId", card.instanceId, "x", x, "y", 75.0)))
        }
        // hUseful: a utility whose effect would do nothing now stays in the hand
        if (!hold) for (move in fromHand) if (!isRefused(move) && (!on("hNoDebt") || !overextends(room, me, self, move))
                && (!on("hUseful") || Usefulness.playDoesSomething(room, me, move, rules))) return move

        if (!attackFirst) {
            val attack = bestAttack(room, me, self, rival, legal)
            if (attack != null) return attack
        }

        // Slow effects, once each per turn
        for (e in legal.entries) {
            if (!on("hActivate")) break
            if (!e.value.contains("activate") || activated.contains(e.key)) continue
            val move = action(ActionType.ACTIVATE_EFFECT, java.util.Map.of<String, Any?>("instanceId", e.key))
            if (isRefused(move)) continue
            return move
        }
        return null
    }

    override fun played(room: GameRoom, action: GameAction) {
        super.played(room, action)
        if (ActionType.ACTIVATE_EFFECT.`is`(action)) activated.add(action.getString("instanceId"))
    }

    override fun newMoment() {
        activated.clear()
    }

    /**
     * The attack worth making now, or null: the most valuable exhausted enemy it destroys without dying, else the
     * rival directly when no active enemy of their Línea frontal can stop it (or they have no lives left to spare).
     */
    private fun bestAttack(room: GameRoom, me: String?, self: PlayerState?, rival: PlayerState?, legal: Map<String, List<String>>): GameAction? {
        if (rival == null) return null
        // hCombat: the whole combat searched (CombatSearch), attacks against the best blocks and the counterattack
        if (on("hCombat")) {
            val decided = booleanArrayOf(false)
            val move = CombatSearch.attack(room, me, rules, decided)
            if (decided[0]) return if (move == null || isRefused(move)) null else move
        }
        @Suppress("UNCHECKED_CAST")
        val legalOf = legal as Map<String?, List<String>>
        @Suppress("UNCHECKED_CAST")
        val targets = LegalActions.attackTargets(room, me) as Map<String?, List<String>>
        val attackers = self!!.field.filter { c -> legalOf.getOrDefault(c.instanceId, java.util.List.of()).contains("attack") }
            .sortedWith(Comparator.comparingInt { c: CardSlot -> EffectEngine.strength(c) }.reversed())
        val theirs = blockers(rival)
        val bestBlocker = theirs.maxOfOrNull { c -> EffectEngine.strength(c) } ?: -1
        val bestBlockerValue = theirs.filter { c -> EffectEngine.strength(c) == bestBlocker }.maxOfOrNull { c -> value(c) } ?: 0.0
        val preyMargin = rules.get("hPreyMargin")
        val directMargin = rules.get("hDirectMargin")
        val evenTrade = rules.get("hEvenTrade")
        for (attacker in attackers) {
            val power = EffectEngine.strength(attacker)
            // A clash it wins against an exhausted enemy (that one cannot block, so the clash is with it or a stronger blocker)
            var prey: CardSlot? = targets.getOrDefault(attacker.instanceId, java.util.List.of())
                .map { id -> onField(rival, id) }.filter { t -> t != null && EffectEngine.strength(t) < power - preyMargin }
                .maxWithOrNull(Comparator.comparingDouble { c: CardSlot? -> value(c) })
            // hPreyGuard (the user's call, 2026-11-05): an active enemy that wins the clash blocks it (or one as strong, when
            // that trade is not worth it): the prey is never reached and the attacker dies for nothing (Ddraig into Rhogler)
            if (prey != null && on("hPreyGuard") && (bestBlocker > power
                    || (bestBlocker == power && value(attacker) > evenTrade * bestBlockerValue))) prey = null
            if (prey != null) {
                val move = action(ActionType.ATTACK, java.util.Map.of<String, Any?>("instanceId", attacker.instanceId, "targetInstanceId", prey.instanceId))
                if (!isRefused(move)) return move
            }
            // An even trade is taken when the attacker is worth no more than the blocker (a stalled table only ends by the deck)
            val even = power == bestBlocker && value(attacker) <= evenTrade * bestBlockerValue
            val unstoppable = bestBlocker < 0 || power > bestBlocker + directMargin || even
                    || EffectEngine.keywords(attacker).any { k -> k.lowercase(Locale.ROOT).startsWith("imbloqueable") }
            val finishing = rival.lifeStack.size <= hits(attacker) && bestBlocker < power
            if (unstoppable || finishing) {
                val move = action(ActionType.ATTACK, java.util.Map.of<String, Any?>("instanceId", attacker.instanceId))
                if (!isRefused(move)) return move
            }
        }
        return null
    }

    // ------------------------------------------------------------------ Choices

    override fun answer(room: GameRoom, me: String?, prompt: Prompt): List<String>? {
        val self = room.getPlayer(me)
        val rival = room.getPlayer(room.getOpponentId(me))
        var choices: List<String?> = choices(prompt)
        val purpose = if (prompt.purpose == null) "" else prompt.purpose!!
        // hUseful: no to an optional effect of its own that would do nothing (a free play of a utility with nothing to
        // do), whether it is asked as a yes or as the effects to chain
        if (on("hUseful") && optionalEffect(prompt) && prompt.min == 0 && !choices.isEmpty()) {
            choices = ArrayList(choices.stream().filter { id -> Usefulness.yesDoesSomething(room, me, prompt, java.util.List.of(id), rules) }.toList())
            if (choices.isEmpty()) return java.util.List.of()
        }
        val chosen: List<String?>? = when (PromptPurpose.parse(purpose).orElse(null)) {
            PromptPurpose.BLOCK -> block(room, self, rival, prompt)
            PromptPurpose.PROVOKE -> provoke(room, rival, prompt)
            // Everything moves up: the Línea frontal attacks, and only an exhausted creature can be attacked there
            PromptPurpose.ADVANCE, PromptPurpose.SPECIAL_FRONT -> advance(self, prompt, choices)
            // Entering the Línea frontal exhausted only gives the rival a target (hAssault: use it anyway)
            PromptPurpose.ASSAULT -> if (on("hAssault") && !choices.isEmpty()) take(choices, Math.max(1, prompt.min)) else if (prompt.min == 0) java.util.List.of() else null
            PromptPurpose.PRIORITY -> respond(room, me, prompt)
            PromptPurpose.ORDER -> take(choices, Math.max(1, prompt.min))
            PromptPurpose.RITUAL -> ritual(self, prompt, choices)
            else -> pick(room, self, rival, prompt, choices)
        }
        @Suppress("UNCHECKED_CAST")
        return chosen as List<String>?
    }

    /**
     * A Ritual from the hand: every creature chosen is sacrificed. The summoner, the least valuable that may be one;
     * then the others, the least valuable group whose levels add up exactly to what is left (`maxLevelSum`).
     * None (which calls the Ritual off) only when no group does.
     */
    private fun ritual(self: PlayerState?, prompt: Prompt, choices: List<String?>): List<String?>? {
        val cards = choices.map { id -> onField(self, id) }.filter { c -> c != null }.map { c -> c!! }
        if (cards.isEmpty()) return if (prompt.min == 0) java.util.List.of() else null
        if (prompt.summonerId == null || prompt.maxLevelSum == null) {
            return java.util.List.of(cards.minWithOrNull(Comparator.comparingDouble { c: CardSlot -> value(c) })!!.instanceId)
        }
        val group = cheapestGroup(cards, prompt.maxLevelSum!!, prompt.max)
        return if (group == null) java.util.List.of() else group.map { it.instanceId }
    }

    /** Moves up the creatures with at least hAdvanceMinStr strength (as many as allowed, the strongest first). */
    private fun advance(self: PlayerState?, prompt: Prompt, choices: List<String?>): List<String?> {
        val min = rules.get("hAdvanceMinStr")
        val up = choices.filter { id ->
            val c = if (self == null) null else onField(self, id)
            c == null || EffectEngine.strength(c) >= min
        }.sortedWith(Comparator.comparingInt { id: String? -> val c = onField(self, id); if (c == null) 0 else EffectEngine.strength(c) }.reversed())
        return take(if (up.size < prompt.min) choices else up, prompt.max)
    }

    /** Responds to the rival's effect with the first one it can (hRespond); lets its own resolve. */
    private fun respond(room: GameRoom, me: String?, prompt: Prompt): List<String?>? {
        val chain = room.chain
        val rivals = !chain.isEmpty() && me!! != chain[chain.size - 1].controllerId
        val choices = choices(prompt)
        if (!on("hRespond") || !rivals || choices.isEmpty()) return if (prompt.min == 0) java.util.List.of() else null
        return java.util.List.of(choices[0])
    }

    /** Blocks with what wins the clash (the weakest of them), trades evenly for a better attacker, or saves its last lives. */
    private fun block(room: GameRoom, self: PlayerState?, rival: PlayerState?, prompt: Prompt): List<String?>? {
        // hBlockBest: the answer that leaves its table best, tried on copies (BlockSearch), instead of the rules below
        if (on("hBlockBest") && self != null) {
            val best = BlockSearch.best(room, self.userId, prompt, rules)
            if (best != null) return best
        }
        if (on("hCombat") && self != null) {
            val searched = CombatSearch.block(room, self.userId, prompt, rules)
            if (searched != null) return searched
        }
        val combat = room.combat
        val attacker = if (combat == null) null else onField(rival, combat.attackerId)
        if (attacker == null) return if (prompt.min == 0) java.util.List.of() else null
        val power = EffectEngine.strength(attacker)
        val mine = prompt.validIds.map { id -> onField(self, id) }.filter { c -> c != null }.map { c -> c!! }
        val winner = mine.filter { c -> EffectEngine.strength(c) > power }.minWithOrNull(Comparator.comparingDouble { c: CardSlot -> value(c) })
        if (winner != null) return java.util.List.of(winner.instanceId)
        val blockEven = rules.get("hBlockEven")
        val even = mine.filter { c -> EffectEngine.strength(c) == power && value(c) <= blockEven * value(attacker) }
            .minWithOrNull(Comparator.comparingDouble { c: CardSlot -> value(c) })
        if (even != null) return java.util.List.of(even.instanceId)
        // Attacking one of its creatures: an unblocked attack only reaches that one
        val direct = combat!!.targetId == null
        if (direct && self!!.lifeStack.size <= hits(attacker) + rules.get("hChumpLives") && !mine.isEmpty()) {
            return java.util.List.of(mine.minWithOrNull(Comparator.comparingDouble { c: CardSlot -> value(c) })!!.instanceId)
        }
        return if (prompt.min == 0) java.util.List.of() else null
    }

    /** Provocar: the enemy forced to block is the most valuable one it destroys, else the weakest. */
    private fun provoke(room: GameRoom, rival: PlayerState?, prompt: Prompt): List<String?>? {
        val combat = room.combat
        val attackerOwner = if (combat == null) null else room.getPlayer(combat.attackerOwnerId)
        val attacker = if (combat == null) null else onField(attackerOwner, combat.attackerId)
        val power = if (attacker == null) 0 else EffectEngine.strength(attacker)
        val enemies = prompt.validIds.map { id -> onField(rival, id) }.filter { c -> c != null }.map { c -> c!! }
        val prey = enemies.filter { c -> EffectEngine.strength(c) < power }.maxWithOrNull(Comparator.comparingDouble { c: CardSlot -> value(c) })
            ?: enemies.minWithOrNull(Comparator.comparingInt { c: CardSlot -> EffectEngine.strength(c) })
        return if (prey == null) null else java.util.List.of(prey.instanceId)
    }

    /**
     * An effect's choice. Yes to a question about its own effect. Enemy cards: the most valuable, as many as allowed.
     * Its own cards: for a loss (a cost, a destruction), the least valuable and as few as allowed (one at least when the
     * effect asks it to pay: it chose to activate it); for a gain, the most valuable, as many as allowed.
     */
    private fun pick(room: GameRoom, self: PlayerState?, rival: PlayerState?, prompt: Prompt, choices: List<String?>): List<String?>? {
        if (choices.isEmpty()) return if (prompt.min == 0) java.util.List.of() else null
        if (prompt.kind == Prompt.Kind.CONFIRM) return if (on("hOptional") || prompt.min > 0) take(choices, Math.max(1, prompt.min)) else java.util.List.of()
        // Cards revealed from its own deck to take ("mira las primeras N cartas…") are its own, though out of the deck
        val reveal = prompt.kind != null && prompt.kind!!.name.startsWith("TAKE_REVEALED")
        val enemy = !reveal && choices.all { id -> self == null || own(self, id) == null }
        val loss = prompt.chosenLabel != null && LOSS.matcher(prompt.chosenLabel!!.lowercase(Locale.ROOT)).find()
        var ranked: MutableList<String?> = ArrayList(choices)
        // hPickPlay: its own cards that are not on the field (to take into the hand, or to give up from it) by what they
        // are worth to play soon, not by their size: a search takes the best card it can play soon, a tribute from the
        // hand gives up the one it would wait longest for
        val byValue: Comparator<String?> = Comparator.comparingDouble { id: String? ->
            val c = card(prompt, id)
            if (on("hPickPlay") && !enemy && self != null && (reveal || own(self, id) != null) && self.field.none { f -> f.instanceId!! == id })
                playValue(room, c, self) else value(c)
        }
        if (!enemy && loss) {
            // hRank: from the hand, the card least worth keeping (copies, then its type's crowd, then playability)
            if (on("hRank") && tributesFromHand(self, prompt, choices)) ranked = ArrayList(HandRank.weakestFirst(room, self!!, choices, rules))
            else ranked.sortWith(if (on("hTributeCreatures") && tributesFromHand(self, prompt, choices))
                Comparator.comparingDouble { id: String? -> tributeOrder(card(prompt, id)) } else byValue)
            val n = if (prompt.min > 0) prompt.min else if (prompt.effectId != null && prompt.purpose == null) 1 else 0
            return take(ranked, Math.min(n, prompt.max))
        }
        // hReduce: a strength reduction goes where it reaches 0 (a removal), or opens a clash; else nowhere (StrengthCuts)
        if (on("hReduce") && enemy) {
            val cut = StrengthCuts.pick(room, self, prompt, choices)
            if (cut != null) return cut
        }
        // hAttackPick: what an attack declared by an effect goes at is a clash, not a removal: an exhausted enemy it beats
        if (on("hAttackPick") && enemy) {
            val attacker = effectAttacker(self, prompt)
            if (attacker != null) {
                val power = EffectEngine.strength(attacker)
                val target = ranked.map { id -> card(prompt, id) }.filter { c -> c != null && EffectEngine.strength(c) < power }
                    .maxWithOrNull(Comparator.comparingDouble { c: CardSlot? -> value(c) })
                return if (target == null) java.util.List.of() else java.util.List.of(target.instanceId)
            }
        }
        ranked.sortWith(byValue.reversed())
        return take(ranked, prompt.max)
    }

    /**
     * A card's worth to play soon: its value times hPickWait (0.7) once if it can be played only at its next turn and
     * twice if later, by the rules and the known state (engine/Playability: cost and reductions, special summon's
     * summoner and materials, a utility's targets).
     */
    private fun playValue(room: GameRoom, c: CardSlot?, self: PlayerState): Double {
        if (c == null) return 0.0
        return value(c) * Math.pow(rules.get("hPickWait"), Playability.level(room, self.userId, c).toDouble())
    }

    companion object {

        /** What a chosen card goes through, by the prompt's label ("Destruir", "Tributar"…): a loss for its owner. */
        private val LOSS = Pattern.compile("destru|sacrific|tribut|descart|envi|agot|reduc|fondo|devuelv|devolv|pierd|baraj|retir|quit")

        /** Offsets that keep the groups apart (above any card's value): creatures after the cards acting from there, then the rest. */
        private const val CREATURES_GROUP = 1000.0
        private const val OTHERS_GROUP = 2000.0

        /** The least valuable group of at most `max` cards whose levels add up exactly to `target`; null if none. */
        private fun cheapestGroup(cards: List<CardSlot>, target: Int, max: Int): List<CardSlot>? {
            val n = Math.min(cards.size, 12)
            var best: List<CardSlot>? = null
            var bestValue = Double.MAX_VALUE
            for (mask in 1 until (1 shl n)) {
                if (Integer.bitCount(mask) > max) continue
                val group: MutableList<CardSlot> = ArrayList()
                for (i in 0 until n) if ((mask and (1 shl i)) != 0) group.add(cards[i])
                // DoubleStream.sum (compensated), as before
                val v = group.stream().mapToDouble { c -> value(c) }.sum()
                if (v < bestValue && addsUp(group, target)) {
                    bestValue = v
                    best = group
                }
            }
            return best
        }

        /** The cards' levels add up exactly to `target`, each counting as any level it can (as the engine checks it). */
        private fun addsUp(group: List<CardSlot>, target: Int): Boolean {
            var sums: MutableSet<Int> = HashSet(java.util.List.of(0))
            for (c in group) {
                val options: List<Int> = if (EffectEngine.levels(c).isEmpty()) java.util.List.of(0) else EffectEngine.levels(c)
                val next: MutableSet<Int> = HashSet()
                for (s in sums) for (l in options) next.add(s + l)
                sums = next
            }
            return sums.contains(target)
        }

        /**
         * The creature of its own an effect makes declare an attack, when the prompt asks what it attacks ("X declara
         * ataque: elige una criatura agotada a la que atacar, o ninguna para atacar a tu rival"); null for other prompts.
         */
        @JvmStatic
        fun effectAttacker(self: PlayerState?, prompt: Prompt): CardSlot? {
            val text = if (prompt.instruction == null) "" else prompt.instruction!!
            val at = text.indexOf(" declara ataque")
            if (self == null || at <= 0 || "Atacar" != prompt.chosenLabel) return null
            val name = text.substring(0, at)
            return self.field.filter { c -> name == c.cardName && "creature" == c.cardType }
                .maxWithOrNull(Comparator.comparingInt { c: CardSlot -> EffectEngine.strength(c) })
        }

        /**
         * A choice to use an optional effect of its own: the effects to chain, a revealed life to play, or the yes of an
         * effect. Not the yeses of the rules (moving up to the Línea frontal, reinforcing…): those carry a purpose.
         */
        @JvmStatic
        fun optionalEffect(prompt: Prompt): Boolean {
            val purpose = if (prompt.purpose == null) "" else prompt.purpose!!
            return PromptPurpose.OPTIONAL.`is`(purpose) || PromptPurpose.LIFE_PLAY.`is`(purpose)
                    || (prompt.kind == Prompt.Kind.CONFIRM && purpose.isEmpty() && prompt.effectId != null)
        }

        /** The cards to choose go from the hand to the Tributos ("tributa N cartas de tu mano"). */
        @JvmStatic
        fun tributesFromHand(self: PlayerState?, prompt: Prompt, choices: List<String?>): Boolean {
            val label = if (prompt.chosenLabel == null) "" else prompt.chosenLabel!!.lowercase(Locale.ROOT)
            return self != null && label.contains("tribut")
                    && choices.all { id -> self.hand.any { c -> id!! == c.instanceId } }
        }

        /**
         * Which card to send from the hand to the Tributos first (lowest first). There a creature still counts: effects take
         * it back, summon it or count it, and some act from there. A utility or structure does nothing there, so it stays in
         * the hand to be played (designer, 2026-11-04), unless it acts from the Tributos too. First the cards that act from
         * there, then the other creatures, then the rest; each group the least valuable first.
         */
        @JvmStatic
        fun tributeOrder(c: CardSlot?): Double {
            if (c == null) return 0.0
            val acts = actsFromTributes(c)
            val group = if (acts) 0.0 else if ("creature" == c.cardType) CREATURES_GROUP else OTHERS_GROUP
            return group + value(c)
        }

        /**
         * hNoDebt: the move's printed price (the cost of a summon or play, the special cost of a Ritual; reductions not
         * counted) would leave it below 0 gems. The heuristic finishes the turn in the look-ahead's tries: without this it
         * summons all it can there, every candidate carries that overextend and its penalty (overExtendPow) swamps what
         * tells them apart. Going below 0 stays a candidate of the search's own.
         */
        @JvmStatic
        fun overextends(room: GameRoom, me: String?, self: PlayerState?, move: GameAction): Boolean {
            val id = move.getString("instanceId")
            val card = if (id == null || self == null) null else self.hand.firstOrNull { c -> id == c.instanceId }
            if (card == null) return false
            val price = if (ActionType.RITUAL_SUMMON.`is`(move)) card.specialCost else card.cost
            return price != null && price > 0 && EffectEngine.gemsOf(room, me) - price < 0
        }

        /** The card has an effect that works from the Tributos, or counts as in the hand from there for special summons. */
        @JvmStatic
        fun actsFromTributes(c: CardSlot): Boolean {
            return CardEffects.specialFromTributes(c) || CardEffects.of(c).any { e -> Zone.TRIBUTE_ZONE.`is`(e.sourceZone) }
        }

        // ------------------------------------------------------------------ Helpers

        /** Where a card from the hand goes on the Retaguardia: across it, one after the other. */
        @JvmStatic
        protected fun summonX(self: PlayerState?): Double {
            return (15 + (self!!.field.size * 13) % 70).toDouble()
        }

        /** The active creatures of the player's Línea frontal: the ones that can block. */
        private fun blockers(player: PlayerState): List<CardSlot> {
            return player.field.filter { c -> "creature" == c.cardType && !c.tapped && EffectEngine.inFrontLine(c) }
        }

        /** The lives a direct attack of this creature takes: 1 plus its Letalidad. */
        private fun hits(attacker: CardSlot): Int {
            var extra = 0
            for (k in EffectEngine.keywords(attacker)) {
                if (!k.lowercase(Locale.ROOT).startsWith("letalidad")) continue
                val m = Pattern.compile("(\\d+)").matcher(k)
                if (m.find()) extra = Math.max(extra, Integer.parseInt(m.group(1)))
            }
            return 1 + extra
        }

        private fun card(prompt: Prompt, id: String?): CardSlot? {
            return prompt.cards.firstOrNull { c -> id!! == c.instanceId }
        }

        private fun take(ids: List<String?>, n: Int): List<String?> {
            return ArrayList(ids.subList(0, Math.max(0, Math.min(n, ids.size))))
        }
    }
}
