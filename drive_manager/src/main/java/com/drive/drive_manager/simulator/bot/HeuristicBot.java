package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.CardEffects;
import com.drive.drive_manager.simulator.engine.Combat;
import com.drive.drive_manager.simulator.engine.EffectEngine;
import com.drive.drive_manager.simulator.engine.LegalActions;
import com.drive.drive_manager.simulator.engine.PendingEffect;
import com.drive.drive_manager.simulator.engine.Prompt;
import com.drive.drive_manager.simulator.engine.PromptPurpose;
import com.drive.drive_manager.simulator.room.ActionType;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;
import com.drive.drive_manager.simulator.room.Zone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The bot that plays to win (phase 1), by simple rules over what it sees, with no look ahead:
 * <ul>
 *   <li>its turn: attacks first (an attack needs 0 or more gems, and summoning spends them), with the attacks it
 *   wins; then Rituals, summons and plays, the most valuable first; then its slow effects, once each; then it ends the turn;</li>
 *   <li>blocking: with a creature that wins the clash, trading an equal one for a better attacker, or anything when
 *   the damage would take its last lives;</li>
 *   <li>choices: enemies, the most valuable; its own cards for a cost or a loss, the least valuable; for a gain, the
 *   most valuable. It activates its optional effects, moves everything up and responds to the rival's effects.</li>
 * </ul>
 * The thresholds of those rules are knobs of its {@link BotSettings} (the "h…" ones, their defaults the rules
 * above), so they can be tuned like the table's weights: they make its big decisions, and the look-ahead bot's
 * simulations play by them.
 */
public class HeuristicBot extends BotPlayer {

    /** What a chosen card goes through, by the prompt's label ("Destruir", "Tributar"…): a loss for its owner. */
    private static final Pattern LOSS = Pattern.compile("destru|sacrific|tribut|descart|envi|agot|reduc|fondo|devuelv|devolv|pierd|baraj|retir|quit");

    /** Cards whose slow effect it activated this turn: once each, so the bot never repeats one forever. */
    private final Set<String> activated = new HashSet<>();
    /** The thresholds of its rules. */
    protected final BotSettings rules;

    public HeuristicBot() {
        this(BotSettings.DEFAULT);
    }

    public HeuristicBot(BotSettings rules) {
        this.rules = rules;
    }

    protected boolean on(String flag) {
        return rules.get(flag) >= 0.5;
    }

    // ------------------------------------------------------------------ Mulligan

    /** Sends to the bottom the cards it could not pay soon (cost hMullCost or more), keeping at least hMullKeep of the five. */
    @Override
    protected List<String> mulligan(GameRoom room, PlayerState self, List<CardSlot> top) {
        List<String> bottom = new ArrayList<>();
        double cost = rules.get("hMullCost");
        int keep = (int) Math.round(rules.get("hMullKeep"));
        top.stream().sorted(Comparator.comparingDouble((CardSlot c) -> c.getCost() == null ? 0 : c.getCost()).reversed())
                .filter(c -> c.getCost() != null && c.getCost() >= cost)
                .limit(Math.max(0, top.size() - keep))
                .forEach(c -> bottom.add(c.getInstanceId()));
        return bottom;
    }

    // ------------------------------------------------------------------ Its turn

    @Override
    protected GameAction mainMove(GameRoom room, String me) {
        Map<String, List<String>> legal = LegalActions.of(room, me);
        PlayerState self = room.getPlayer(me);
        PlayerState rival = room.getPlayer(room.getOpponentId(me));

        boolean attackFirst = on("hAttackFirst");
        if (attackFirst) {
            GameAction attack = bestAttack(room, me, self, rival, legal);
            if (attack != null) return attack;
        }

        // Rituals, summons and plays: the most valuable first, keeping hHold cards in the hand
        List<GameAction> fromHand = new ArrayList<>();
        boolean hold = self.getHand().size() <= Math.round(rules.get("hHold"));
        List<CardSlot> hand = new ArrayList<>(self.getHand());
        hand.sort(Comparator.comparingDouble(BotPlayer::value).reversed());
        // hStructFirst: structures before the Rituals and summons (their effects pay off on the invocations that follow)
        if (on("hStructFirst")) for (CardSlot card : hand) {
            if ("structure".equals(card.getCardType()) && legal.getOrDefault(card.getInstanceId(), List.of()).contains("play"))
                fromHand.add(action(ActionType.PLAY_CARD, Map.of("instanceId", card.getInstanceId(), "x", summonX(self), "y", 75.0)));
        }
        for (String id : legal.keySet()) {
            if (legal.get(id).contains("ritual")) fromHand.add(action(ActionType.RITUAL_SUMMON, Map.of("instanceId", id)));
        }
        double x = summonX(self);
        for (CardSlot card : hand) {
            List<String> can = legal.getOrDefault(card.getInstanceId(), List.of());
            if (can.contains("summon")) fromHand.add(action(ActionType.NORMAL_SUMMON, Map.of("instanceId", card.getInstanceId(), "x", x, "y", 75.0)));
            if (can.contains("play")) fromHand.add(action(ActionType.PLAY_CARD, Map.of("instanceId", card.getInstanceId(), "x", x, "y", 75.0)));
        }
        // hUseful: a utility whose effect would do nothing now stays in the hand
        if (!hold) for (GameAction move : fromHand) if (!isRefused(move) && (!on("hNoDebt") || !overextends(room, me, self, move))
                && (!on("hUseful") || Usefulness.playDoesSomething(room, me, move, rules))) return move;

        if (!attackFirst) {
            GameAction attack = bestAttack(room, me, self, rival, legal);
            if (attack != null) return attack;
        }

        // Slow effects, once each per turn
        for (Map.Entry<String, List<String>> e : legal.entrySet()) {
            if (!on("hActivate")) break;
            if (!e.getValue().contains("activate") || activated.contains(e.getKey())) continue;
            GameAction move = action(ActionType.ACTIVATE_EFFECT, Map.of("instanceId", e.getKey()));
            if (isRefused(move)) continue;
            return move;
        }
        return null;
    }

    @Override
    public void played(GameRoom room, GameAction action) {
        super.played(room, action);
        if (ActionType.ACTIVATE_EFFECT.is(action)) activated.add(action.getString("instanceId"));
    }

    @Override
    protected void newMoment() {
        activated.clear();
    }

    /**
     * The attack worth making now, or null: the most valuable exhausted enemy it destroys without dying, else the
     * rival directly when no active enemy of their Línea frontal can stop it (or they have no lives left to spare).
     */
    private GameAction bestAttack(GameRoom room, String me, PlayerState self, PlayerState rival, Map<String, List<String>> legal) {
        if (rival == null) return null;
        // hCombat: the whole combat searched (CombatSearch), attacks against the best blocks and the counterattack
        if (on("hCombat")) {
            boolean[] decided = {false};
            GameAction move = CombatSearch.attack(room, me, rules, decided);
            if (decided[0]) return move == null || isRefused(move) ? null : move;
        }
        Map<String, List<String>> targets = LegalActions.attackTargets(room, me);
        List<CardSlot> attackers = self.getField().stream().filter(c -> legal.getOrDefault(c.getInstanceId(), List.of()).contains("attack"))
                .sorted(Comparator.comparingInt(EffectEngine::strength).reversed()).toList();
        List<CardSlot> theirs = blockers(rival);
        int bestBlocker = theirs.stream().mapToInt(EffectEngine::strength).max().orElse(-1);
        double bestBlockerValue = theirs.stream().filter(c -> EffectEngine.strength(c) == bestBlocker).mapToDouble(BotPlayer::value).max().orElse(0);
        double preyMargin = rules.get("hPreyMargin");
        double directMargin = rules.get("hDirectMargin");
        double evenTrade = rules.get("hEvenTrade");
        for (CardSlot attacker : attackers) {
            int power = EffectEngine.strength(attacker);
            // A clash it wins against an exhausted enemy (that one cannot block, so the clash is with it or a stronger blocker)
            CardSlot prey = targets.getOrDefault(attacker.getInstanceId(), List.of()).stream()
                    .map(id -> onField(rival, id)).filter(t -> t != null && EffectEngine.strength(t) < power - preyMargin)
                    .max(Comparator.comparingDouble(BotPlayer::value)).orElse(null);
            // hPreyGuard (the user's call, 2026-11-05): an active enemy that wins the clash blocks it (or one as strong, when
            // that trade is not worth it): the prey is never reached and the attacker dies for nothing (Ddraig into Rhogler)
            if (prey != null && on("hPreyGuard") && (bestBlocker > power
                    || (bestBlocker == power && value(attacker) > evenTrade * bestBlockerValue))) prey = null;
            if (prey != null) {
                GameAction move = action(ActionType.ATTACK, Map.of("instanceId", attacker.getInstanceId(), "targetInstanceId", prey.getInstanceId()));
                if (!isRefused(move)) return move;
            }
            // An even trade is taken when the attacker is worth no more than the blocker (a stalled table only ends by the deck)
            boolean even = power == bestBlocker && value(attacker) <= evenTrade * bestBlockerValue;
            boolean unstoppable = bestBlocker < 0 || power > bestBlocker + directMargin || even
                    || EffectEngine.keywords(attacker).stream().anyMatch(k -> k.toLowerCase(Locale.ROOT).startsWith("imbloqueable"));
            boolean finishing = rival.getLifeStack().size() <= hits(attacker) && bestBlocker < power;
            if (unstoppable || finishing) {
                GameAction move = action(ActionType.ATTACK, Map.of("instanceId", attacker.getInstanceId()));
                if (!isRefused(move)) return move;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ Choices

    @Override
    protected List<String> answer(GameRoom room, String me, Prompt prompt) {
        PlayerState self = room.getPlayer(me);
        PlayerState rival = room.getPlayer(room.getOpponentId(me));
        List<String> choices = choices(prompt);
        String purpose = prompt.getPurpose() == null ? "" : prompt.getPurpose();
        // hUseful: no to an optional effect of its own that would do nothing (a free play of a utility with nothing to
        // do), whether it is asked as a yes or as the effects to chain
        if (on("hUseful") && optionalEffect(prompt) && prompt.getMin() == 0 && !choices.isEmpty()) {
            choices = new ArrayList<>(choices.stream().filter(id -> Usefulness.yesDoesSomething(room, me, prompt, List.of(id), rules)).toList());
            if (choices.isEmpty()) return List.of();
        }
        return switch (PromptPurpose.parse(purpose).orElse(null)) {
            case BLOCK -> block(room, self, rival, prompt);
            case PROVOKE -> provoke(room, rival, prompt);
            // Everything moves up: the Línea frontal attacks, and only an exhausted creature can be attacked there
            case ADVANCE, SPECIAL_FRONT -> advance(self, prompt, choices);
            // Entering the Línea frontal exhausted only gives the rival a target (hAssault: use it anyway)
            case ASSAULT -> on("hAssault") && !choices.isEmpty() ? take(choices, Math.max(1, prompt.getMin())) : prompt.getMin() == 0 ? List.of() : null;
            case PRIORITY -> respond(room, me, prompt);
            case ORDER -> take(choices, Math.max(1, prompt.getMin()));
            case RITUAL -> ritual(self, prompt, choices);
            case null, default -> pick(room, self, rival, prompt, choices);
        };
    }

    /**
     * A Ritual from the hand: every creature chosen is sacrificed. The summoner, the least valuable that may be one;
     * then the others, the least valuable group whose levels add up exactly to what is left ({@code maxLevelSum}).
     * None (which calls the Ritual off) only when no group does.
     */
    private List<String> ritual(PlayerState self, Prompt prompt, List<String> choices) {
        List<CardSlot> cards = choices.stream().map(id -> onField(self, id)).filter(c -> c != null).toList();
        if (cards.isEmpty()) return prompt.getMin() == 0 ? List.of() : null;
        if (prompt.getSummonerId() == null || prompt.getMaxLevelSum() == null) {
            return List.of(cards.stream().min(Comparator.comparingDouble(BotPlayer::value)).get().getInstanceId());
        }
        List<CardSlot> group = cheapestGroup(cards, prompt.getMaxLevelSum(), prompt.getMax());
        return group == null ? List.of() : group.stream().map(CardSlot::getInstanceId).toList();
    }

    /** The least valuable group of at most {@code max} cards whose levels add up exactly to {@code target}; null if none. */
    private static List<CardSlot> cheapestGroup(List<CardSlot> cards, int target, int max) {
        int n = Math.min(cards.size(), 12);
        List<CardSlot> best = null;
        double bestValue = Double.MAX_VALUE;
        for (int mask = 1; mask < (1 << n); mask++) {
            if (Integer.bitCount(mask) > max) continue;
            List<CardSlot> group = new ArrayList<>();
            for (int i = 0; i < n; i++) if ((mask & (1 << i)) != 0) group.add(cards.get(i));
            double v = group.stream().mapToDouble(BotPlayer::value).sum();
            if (v < bestValue && addsUp(group, target)) {
                bestValue = v;
                best = group;
            }
        }
        return best;
    }

    /** The cards' levels add up exactly to {@code target}, each counting as any level it can (as the engine checks it). */
    private static boolean addsUp(List<CardSlot> group, int target) {
        Set<Integer> sums = new HashSet<>(List.of(0));
        for (CardSlot c : group) {
            List<Integer> options = EffectEngine.levels(c).isEmpty() ? List.of(0) : EffectEngine.levels(c);
            Set<Integer> next = new HashSet<>();
            for (int s : sums) for (int l : options) next.add(s + l);
            sums = next;
        }
        return sums.contains(target);
    }

    /** Moves up the creatures with at least hAdvanceMinStr strength (as many as allowed, the strongest first). */
    private List<String> advance(PlayerState self, Prompt prompt, List<String> choices) {
        double min = rules.get("hAdvanceMinStr");
        List<String> up = choices.stream().filter(id -> {
            CardSlot c = self == null ? null : onField(self, id);
            return c == null || EffectEngine.strength(c) >= min;
        }).sorted(Comparator.comparingInt((String id) -> { CardSlot c = onField(self, id); return c == null ? 0 : EffectEngine.strength(c); }).reversed()).toList();
        return take(up.size() < prompt.getMin() ? choices : up, prompt.getMax());
    }

    /** Responds to the rival's effect with the first one it can (hRespond); lets its own resolve. */
    private List<String> respond(GameRoom room, String me, Prompt prompt) {
        List<PendingEffect> chain = room.getChain();
        boolean rivals = !chain.isEmpty() && !me.equals(chain.get(chain.size() - 1).getControllerId());
        List<String> choices = choices(prompt);
        if (!on("hRespond") || !rivals || choices.isEmpty()) return prompt.getMin() == 0 ? List.of() : null;
        return List.of(choices.get(0));
    }

    /** Blocks with what wins the clash (the weakest of them), trades evenly for a better attacker, or saves its last lives. */
    private List<String> block(GameRoom room, PlayerState self, PlayerState rival, Prompt prompt) {
        // hBlockBest: the answer that leaves its table best, tried on copies (BlockSearch), instead of the rules below
        if (on("hBlockBest") && self != null) {
            List<String> best = BlockSearch.best(room, self.getUserId(), prompt, rules);
            if (best != null) return best;
        }
        if (on("hCombat") && self != null) {
            List<String> searched = CombatSearch.block(room, self.getUserId(), prompt, rules);
            if (searched != null) return searched;
        }
        Combat combat = room.getCombat();
        CardSlot attacker = combat == null ? null : onField(rival, combat.getAttackerId());
        if (attacker == null) return prompt.getMin() == 0 ? List.of() : null;
        int power = EffectEngine.strength(attacker);
        List<CardSlot> mine = prompt.getValidIds().stream().map(id -> onField(self, id)).filter(c -> c != null).toList();
        CardSlot winner = mine.stream().filter(c -> EffectEngine.strength(c) > power).min(Comparator.comparingDouble(BotPlayer::value)).orElse(null);
        if (winner != null) return List.of(winner.getInstanceId());
        double blockEven = rules.get("hBlockEven");
        CardSlot even = mine.stream().filter(c -> EffectEngine.strength(c) == power && value(c) <= blockEven * value(attacker))
                .min(Comparator.comparingDouble(BotPlayer::value)).orElse(null);
        if (even != null) return List.of(even.getInstanceId());
        // Attacking one of its creatures: an unblocked attack only reaches that one
        boolean direct = combat.getTargetId() == null;
        if (direct && self.getLifeStack().size() <= hits(attacker) + rules.get("hChumpLives") && !mine.isEmpty()) {
            return List.of(mine.stream().min(Comparator.comparingDouble(BotPlayer::value)).get().getInstanceId());
        }
        return prompt.getMin() == 0 ? List.of() : null;
    }

    /** Provocar: the enemy forced to block is the most valuable one it destroys, else the weakest. */
    private List<String> provoke(GameRoom room, PlayerState rival, Prompt prompt) {
        Combat combat = room.getCombat();
        PlayerState attackerOwner = combat == null ? null : room.getPlayer(combat.getAttackerOwnerId());
        CardSlot attacker = combat == null ? null : onField(attackerOwner, combat.getAttackerId());
        int power = attacker == null ? 0 : EffectEngine.strength(attacker);
        List<CardSlot> enemies = prompt.getValidIds().stream().map(id -> onField(rival, id)).filter(c -> c != null).toList();
        CardSlot prey = enemies.stream().filter(c -> EffectEngine.strength(c) < power).max(Comparator.comparingDouble(BotPlayer::value))
                .or(() -> enemies.stream().min(Comparator.comparingInt(EffectEngine::strength))).orElse(null);
        return prey == null ? null : List.of(prey.getInstanceId());
    }

    /**
     * An effect's choice. Yes to a question about its own effect. Enemy cards: the most valuable, as many as allowed.
     * Its own cards: for a loss (a cost, a destruction), the least valuable and as few as allowed (one at least when the
     * effect asks it to pay: it chose to activate it); for a gain, the most valuable, as many as allowed.
     */
    private List<String> pick(GameRoom room, PlayerState self, PlayerState rival, Prompt prompt, List<String> choices) {
        if (choices.isEmpty()) return prompt.getMin() == 0 ? List.of() : null;
        if (prompt.getKind() == Prompt.Kind.CONFIRM) return on("hOptional") || prompt.getMin() > 0 ? take(choices, Math.max(1, prompt.getMin())) : List.of();
        // Cards revealed from its own deck to take ("mira las primeras N cartas…") are its own, though out of the deck
        boolean reveal = prompt.getKind() != null && prompt.getKind().name().startsWith("TAKE_REVEALED");
        boolean enemy = !reveal && choices.stream().allMatch(id -> self == null || own(self, id) == null);
        boolean loss = prompt.getChosenLabel() != null && LOSS.matcher(prompt.getChosenLabel().toLowerCase(Locale.ROOT)).find();
        List<String> ranked = new ArrayList<>(choices);
        // hPickPlay: its own cards that are not on the field (to take into the hand, or to give up from it) by what they
        // are worth to play soon, not by their size: a search takes the best card it can play soon, a tribute from the
        // hand gives up the one it would wait longest for
        Comparator<String> byValue = Comparator.comparingDouble(id -> {
            CardSlot c = card(prompt, id);
            return on("hPickPlay") && !enemy && self != null && (reveal || own(self, id) != null) && self.getField().stream().noneMatch(f -> f.getInstanceId().equals(id))
                    ? playValue(room, c, self) : value(c);
        });
        if (!enemy && loss) {
            // hRank: from the hand, the card least worth keeping (copies, then its type's crowd, then playability)
            if (on("hRank") && tributesFromHand(self, prompt, choices)) ranked = new ArrayList<>(HandRank.weakestFirst(room, self, choices, rules));
            else ranked.sort(on("hTributeCreatures") && tributesFromHand(self, prompt, choices)
                    ? Comparator.comparingDouble(id -> tributeOrder(card(prompt, id))) : byValue);
            int n = prompt.getMin() > 0 ? prompt.getMin() : prompt.getEffectId() != null && prompt.getPurpose() == null ? 1 : 0;
            return take(ranked, Math.min(n, prompt.getMax()));
        }
        // hReduce: a strength reduction goes where it reaches 0 (a removal), or opens a clash; else nowhere (StrengthCuts)
        if (on("hReduce") && enemy) {
            List<String> cut = StrengthCuts.pick(room, self, prompt, choices);
            if (cut != null) return cut;
        }
        // hAttackPick: what an attack declared by an effect goes at is a clash, not a removal: an exhausted enemy it beats
        if (on("hAttackPick") && enemy) {
            CardSlot attacker = effectAttacker(self, prompt);
            if (attacker != null) {
                int power = EffectEngine.strength(attacker);
                return ranked.stream().map(id -> card(prompt, id)).filter(c -> c != null && EffectEngine.strength(c) < power)
                        .max(Comparator.comparingDouble(BotPlayer::value)).map(c -> List.of(c.getInstanceId())).orElse(List.of());
            }
        }
        ranked.sort(byValue.reversed());
        return take(ranked, prompt.getMax());
    }

    /**
     * The creature of its own an effect makes declare an attack, when the prompt asks what it attacks ("X declara
     * ataque: elige una criatura agotada a la que atacar, o ninguna para atacar a tu rival"); null for other prompts.
     */
    static CardSlot effectAttacker(PlayerState self, Prompt prompt) {
        String text = prompt.getInstruction() == null ? "" : prompt.getInstruction();
        int at = text.indexOf(" declara ataque");
        if (self == null || at <= 0 || !"Atacar".equals(prompt.getChosenLabel())) return null;
        String name = text.substring(0, at);
        return self.getField().stream().filter(c -> name.equals(c.getCardName()) && "creature".equals(c.getCardType()))
                .max(Comparator.comparingInt(EffectEngine::strength)).orElse(null);
    }

    /**
     * A choice to use an optional effect of its own: the effects to chain, a revealed life to play, or the yes of an
     * effect. Not the yeses of the rules (moving up to the Línea frontal, reinforcing…): those carry a purpose.
     */
    static boolean optionalEffect(Prompt prompt) {
        String purpose = prompt.getPurpose() == null ? "" : prompt.getPurpose();
        return PromptPurpose.OPTIONAL.is(purpose) || PromptPurpose.LIFE_PLAY.is(purpose)
                || (prompt.getKind() == Prompt.Kind.CONFIRM && purpose.isEmpty() && prompt.getEffectId() != null);
    }

    /**
     * A card's worth to play soon: its value times hPickWait (0.7) once if it can be played only at its next turn and
     * twice if later, by the rules and the known state (engine/Playability: cost and reductions, special summon's
     * summoner and materials, a utility's targets).
     */
    private double playValue(GameRoom room, CardSlot c, PlayerState self) {
        if (c == null) return 0;
        return value(c) * Math.pow(rules.get("hPickWait"), com.drive.drive_manager.simulator.engine.Playability.level(room, self.getUserId(), c));
    }

    /** The cards to choose go from the hand to the Tributos ("tributa N cartas de tu mano"). */
    static boolean tributesFromHand(PlayerState self, Prompt prompt, List<String> choices) {
        String label = prompt.getChosenLabel() == null ? "" : prompt.getChosenLabel().toLowerCase(Locale.ROOT);
        return self != null && label.contains("tribut")
                && choices.stream().allMatch(id -> self.getHand().stream().anyMatch(c -> id.equals(c.getInstanceId())));
    }

    /** Offsets that keep the groups apart (above any card's value): creatures after the cards acting from there, then the rest. */
    private static final double CREATURES_GROUP = 1000, OTHERS_GROUP = 2000;

    /**
     * Which card to send from the hand to the Tributos first (lowest first). There a creature still counts: effects take
     * it back, summon it or count it, and some act from there. A utility or structure does nothing there, so it stays in
     * the hand to be played (designer, 2026-11-04), unless it acts from the Tributos too. First the cards that act from
     * there, then the other creatures, then the rest; each group the least valuable first.
     */
    static double tributeOrder(CardSlot c) {
        if (c == null) return 0;
        boolean acts = actsFromTributes(c);
        double group = acts ? 0 : "creature".equals(c.getCardType()) ? CREATURES_GROUP : OTHERS_GROUP;
        return group + value(c);
    }

    /**
     * hNoDebt: the move's printed price (the cost of a summon or play, the special cost of a Ritual; reductions not
     * counted) would leave it below 0 gems. The heuristic finishes the turn in the look-ahead's tries: without this it
     * summons all it can there, every candidate carries that overextend and its penalty (overExtendPow) swamps what
     * tells them apart. Going below 0 stays a candidate of the search's own.
     */
    static boolean overextends(GameRoom room, String me, PlayerState self, GameAction move) {
        String id = move.getString("instanceId");
        CardSlot card = id == null || self == null ? null : self.getHand().stream().filter(c -> id.equals(c.getInstanceId())).findFirst().orElse(null);
        if (card == null) return false;
        Integer price = ActionType.RITUAL_SUMMON.is(move) ? card.getSpecialCost() : card.getCost();
        return price != null && price > 0 && com.drive.drive_manager.simulator.engine.EffectEngine.gemsOf(room, me) - price < 0;
    }

    /** The card has an effect that works from the Tributos, or counts as in the hand from there for special summons. */
    static boolean actsFromTributes(CardSlot c) {
        return CardEffects.specialFromTributes(c) || CardEffects.of(c).stream().anyMatch(e -> Zone.TRIBUTE_ZONE.is(e.sourceZone()));
    }

    // ------------------------------------------------------------------ Helpers

    /** Where a card from the hand goes on the Retaguardia: across it, one after the other. */
    protected static double summonX(PlayerState self) {
        return 15 + (self.getField().size() * 13) % 70;
    }

    /** The active creatures of the player's Línea frontal: the ones that can block. */
    private static List<CardSlot> blockers(PlayerState player) {
        return player.getField().stream().filter(c -> "creature".equals(c.getCardType()) && !c.isTapped() && EffectEngine.inFrontLine(c)).toList();
    }

    /** The lives a direct attack of this creature takes: 1 plus its Letalidad. */
    private static int hits(CardSlot attacker) {
        int extra = 0;
        for (String k : EffectEngine.keywords(attacker)) {
            if (!k.toLowerCase(Locale.ROOT).startsWith("letalidad")) continue;
            Matcher m = Pattern.compile("(\\d+)").matcher(k);
            if (m.find()) extra = Math.max(extra, Integer.parseInt(m.group(1)));
        }
        return 1 + extra;
    }

    private static CardSlot card(Prompt prompt, String id) {
        return prompt.getCards().stream().filter(c -> id.equals(c.getInstanceId())).findFirst().orElse(null);
    }

    private static List<String> take(List<String> ids, int n) {
        return new ArrayList<>(ids.subList(0, Math.max(0, Math.min(n, ids.size()))));
    }
}
