package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reactivo–Modificadores of a cost ("Reacciones, modificadores y Reemplazos"): applied right before the event they change
 * (a special summon, playing a card), they lower its cost and keep the event as it is. Some ask for a payment of their own
 * ("puedes agotar esta carta…", "puedes enviar 5 de tus tributos [dragón]…"). Cards declare them in {@link CardEffects}.
 */
public final class CostModifiers {

    /** The event whose cost it changes. */
    public enum Event { SPECIAL_SUMMON, PLAY, SUMMON }

    /**
     * Whose it is: the special summon's summoner ("cuando esta carta fuera a invocar…"), any of the player's cards on the
     * field ("cuando fueras a invocar…", a structure) or the card being played itself ("cuando fueras a jugar esta carta").
     */
    public enum Holder { SUMMONER, FIELD, SELF }

    public enum PaymentKind { NONE, TAP_SELF, TRIBUTES_TO_DECK_BOTTOM, TRIBUTE_AS_RESOURCE, TAP_CARD, SELF_DAMAGE }

    /** What the modifier asks for, if anything. */
    public record Payment(PaymentKind kind, int count, CardFilter filter) {
        public static Payment none() {
            return new Payment(PaymentKind.NONE, 0, null);
        }

        /** "puedes agotar esta carta": the holder, active. */
        public static Payment tapSelf() {
            return new Payment(PaymentKind.TAP_SELF, 0, null);
        }

        /** "puedes enviar N de tus tributos [filtro] al fondo de tu mazo": the player picks which. */
        public static Payment tributesToDeckBottom(int count, CardFilter filter) {
            return new Payment(PaymentKind.TRIBUTES_TO_DECK_BOTTOM, count, filter);
        }

        /** "puedes añadir 1 de tus tributos [filtro] como recurso de esta carta": the summoner, before the summon. */
        public static Payment tributeAsResource(CardFilter filter) {
            return new Payment(PaymentKind.TRIBUTE_AS_RESOURCE, 1, filter);
        }

        /** "puedes agotar 1 carta [filtro] bajo tu control": the player picks which (active, on the field). */
        public static Payment tapCard(CardFilter filter) {
            return new Payment(PaymentKind.TAP_CARD, 1, filter);
        }

        /**
         * "hasta N veces: puedes infligirte X de daño para reducir en 1 el coste especial" (Biok): direct damage by effect to oneself, as many
         * times as the player picks (up to {@code count}); {@code filter} unused, the damage is in {@link Payment#damage()}.
         */
        public static Payment selfDamage(int times, int damage) {
            return new Payment(PaymentKind.SELF_DAMAGE, times, CardFilter.any().withMaxStrength(damage));
        }

        /** The damage of a {@link PaymentKind#SELF_DAMAGE} payment. */
        public int damage() {
            return filter == null || filter.maxStrength() == null ? 0 : filter.maxStrength();
        }

        /** The player has to choose the cards (the others are applied on their own). */
        public boolean needsChoice() {
            return kind == PaymentKind.TRIBUTES_TO_DECK_BOTTOM || kind == PaymentKind.TRIBUTE_AS_RESOURCE || kind == PaymentKind.TAP_CARD
                    || kind == PaymentKind.SELF_DAMAGE;
        }
    }

    /**
     * @param label     how it is printed ("Turno Aliado", "Todos Los Turnos"), for the choice
     * @param kinds     the special summon methods it applies to ("evolution"…); empty: all of them
     * @param allyTurn  only on its controller's turn
     * @param condition the rest of its condition ("si tienes 5 o más tributos [dragón]"), on its holder
     * @param reduce    how much it lowers the cost
     * @param card      the card whose cost it lowers ("1 criatura Azul nivel 3 o mayor"); null: any
     * @param amount    a reduction read from the game, added to {@code reduce} ("en 1 por cada [brujería] en tu descarte"); null: none
     */
    public record CostModifier(String label, Event event, Set<String> kinds, Holder holder, boolean allyTurn,
                               Condition condition, Payment payment, int reduce, EffectScript.Limit limit, CardFilter card, Amount amount) {
        public CostModifier(String label, Event event, Set<String> kinds, Holder holder, boolean allyTurn, Condition condition, Payment payment,
                            int reduce) {
            this(label, event, kinds, holder, allyTurn, condition, payment, reduce, EffectScript.Limit.NONE);
        }

        public CostModifier(String label, Event event, Set<String> kinds, Holder holder, boolean allyTurn, Condition condition, Payment payment,
                            int reduce, EffectScript.Limit limit) {
            this(label, event, kinds, holder, allyTurn, condition, payment, reduce, limit, null, null);
        }

        /** How much it lowers the cost now. */
        public int reduceNow(GameRoom room, String controller) {
            return reduce + (amount == null ? 0 : amount.of(room, controller));
        }
    }

    /** The mark of a limited modifier used this turn (between copies: by the card's name). */
    static String usageKey(PlayerState player, Use use) {
        // A Definitive one counts as the player's Definitive effect of the turn (only 1 per turn, whichever card)
        if (use.modifier().limit() == EffectScript.Limit.DEFINITIVE) return player.getUserId() + "|" + EffectEngine.DEFINITIVE_USED;
        return player.getUserId() + "|cost-modifier|" + use.holder().getCardName().trim().toLowerCase(Locale.ROOT);
    }

    /** A limited modifier was used: it is not offered again this turn. */
    static void markUsed(GameRoom room, PlayerState player, Use use) {
        if (use.modifier().limit() != EffectScript.Limit.NONE) room.getUsedThisTurn().add(usageKey(player, use));
    }

    /** A modifier that applies now, with the card that has it. */
    public record Use(CostModifier modifier, CardSlot holder) {}

    private static final Map<String, List<CostModifier>> BY_NAME = new HashMap<>();

    static {
        // The cards register their modifiers when CardEffects loads: load it before the first lookup
        CardEffects.load();
    }

    private CostModifiers() {}

    static void register(String cardName, CostModifier... modifiers) {
        BY_NAME.computeIfAbsent(cardName.trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).addAll(List.of(modifiers));
    }

    /** The card's modifiers, by its name in lower case. */
    static List<CostModifier> of(String name) {
        return BY_NAME.getOrDefault(name, List.of());
    }

    /** The names of the cards with modifiers. */
    static java.util.Set<String> names() {
        return BY_NAME.keySet();
    }

    private static List<CostModifier> of(CardSlot card) {
        if (card == null || card.getCardName() == null) return List.of();
        return BY_NAME.getOrDefault(card.getCardName().trim().toLowerCase(Locale.ROOT), List.of());
    }

    /**
     * The modifiers that apply now to that event: their holder is where it has to be (active, to exhaust it), it is its
     * controller's turn when they say so, their condition holds and their payment can be made. With no summoner chosen
     * yet, any card of the field could be it (to tell whether the summon is affordable at all).
     */
    static List<Use> applicable(GameRoom room, PlayerState player, Event event, String kind, CardSlot card, CardSlot summoner) {
        if (player == null) return List.of();
        List<Use> uses = new ArrayList<>();
        List<CardSlot> holders = new ArrayList<>();
        // The card itself ("cuando fueras a jugar / invocar esta carta"), while it is not on the field yet
        if (card != null && !player.getField().contains(card)) holders.add(card);
        holders.addAll(player.getField());
        for (CardSlot holder : holders) {
            // A card face down on the field has no effects (the one being played is not on the field yet)
            if (holder != card && holder.isFaceDown()) continue;
            for (CostModifier m : of(holder)) {
                if (m.event() != event || (!m.kinds().isEmpty() && !m.kinds().contains(kind))) continue;
                if (m.card() != null && (card == null || !m.card().matches(card))) continue;
                boolean placed = switch (m.holder()) {
                    case SELF -> holder == card;
                    case SUMMONER -> summoner == null ? holder != card : holder == summoner;
                    case FIELD -> holder != card;
                };
                if (!placed) continue;
                if (m.allyTurn() && !player.getUserId().equals(room.getCurrentTurnUserId())) continue;
                if (m.condition() != null && !m.condition().test(room, null, holder, player.getUserId())) continue;
                if (!payable(player, m.payment(), holder, card)) continue;
                // "puedes agotar…" the player said no to, for this summon
                if (m.payment().kind() == PaymentKind.TAP_SELF && card != null && room.getUsedThisTurn().contains(tapKey(holder, card, false))) continue;
                Use use = new Use(m, holder);
                if (m.limit() == EffectScript.Limit.DEFINITIVE && room.isFirstTurn()) continue;
                if (m.limit() != EffectScript.Limit.NONE && room.getUsedThisTurn().contains(usageKey(player, use))) continue;
                uses.add(use);
            }
        }
        return uses;
    }

    /** {@code card}: the card whose cost it lowers, never part of the payment ("envía otras 5…": it may be in the Tributos). */
    private static boolean payable(PlayerState player, Payment payment, CardSlot holder, CardSlot card) {
        return switch (payment.kind()) {
            case NONE -> true;
            case TAP_SELF -> !holder.isTapped();
            case TRIBUTES_TO_DECK_BOTTOM, TRIBUTE_AS_RESOURCE -> tributesFor(player, payment, card).size() >= payment.count();
            case TAP_CARD -> player.getField().stream().filter(c -> !c.isTapped() && payment.filter().matches(c)).count() >= payment.count();
            case SELF_DAMAGE -> !player.getLifeStack().isEmpty();
        };
    }

    /** How much the cost could go down now, all of them used (to tell whether it is affordable). */
    static int available(GameRoom room, PlayerState player, Event event, String kind, CardSlot card, CardSlot summoner) {
        return applicable(room, player, event, kind, card, summoner).stream().mapToInt(u -> u.modifier().reduceNow(room, player.getUserId())).sum();
    }

    /** The player's answer to "puedes agotar esta carta para reducir…" for this summon (Bosque Frondoso), until it is used. */
    static String tapKey(CardSlot holder, CardSlot card, boolean yes) {
        return (yes ? "cost-tap|" : "cost-tap-no|") + holder.getInstanceId() + "|" + card.getInstanceId();
    }

    /**
     * "puedes agotar esta carta para reducir el coste especial en 1" is optional: the first one not answered yet for this card, to
     * ask before the summon (null when there is none, or the cost is already 0).
     */
    static Use unansweredTap(GameRoom room, PlayerState player, Event event, String kind, CardSlot card) {
        // Designer ruling 2026-10-31: a cost can be reduced when it is already 0 (it stays 0), so it is asked anyway
        return applicable(room, player, event, kind, card, null).stream()
                .filter(u -> u.modifier().payment().kind() == PaymentKind.TAP_SELF)
                .filter(u -> !room.getUsedThisTurn().contains(tapKey(u.holder(), card, true))
                        && !room.getUsedThisTurn().contains(tapKey(u.holder(), card, false)))
                .findFirst().orElse(null);
    }

    /**
     * Applies those that need no choice while the cost is above 0: a free one (Duregón's) and an exhaustion the player accepted
     * ("puedes agotar"; one not asked, as in a summon by effect, is used when it lowers the cost). Returns the reduction.
     */
    static int applyAutomatic(GameRoom room, PlayerState player, Event event, String kind, CardSlot card, CardSlot summoner, int cost) {
        return applyAutomatic(room, player, event, kind, card, summoner, cost, true);
    }

    /** {@code taps}: whether "puedes agotar esta carta…" ones may be used (a normal summon uses them only when it could not be paid otherwise). */
    static int applyAutomatic(GameRoom room, PlayerState player, Event event, String kind, CardSlot card, CardSlot summoner, int cost, boolean taps) {
        int reduced = 0;
        for (Use use : applicable(room, player, event, kind, card, summoner)) {
            if (use.modifier().payment().needsChoice()) continue;
            if (!taps && use.modifier().payment().kind() == PaymentKind.TAP_SELF) continue;
            // An exhaustion the player accepted is done even when the cost is already 0; the others only while they lower it
            boolean accepted = use.modifier().payment().kind() == PaymentKind.TAP_SELF && card != null
                    && room.getUsedThisTurn().contains(tapKey(use.holder(), card, true));
            if (cost - reduced <= 0 && !accepted) continue;
            if (use.modifier().payment().kind() == PaymentKind.TAP_SELF) {
                room.getUsedThisTurn().remove(tapKey(use.holder(), card, true));
                use.holder().setTapped(true);
                EffectEngine.record(room, GameEvent.TAP, player.getUserId(), use.holder(), null, null, null, null,
                        player.getUsername() + " agota " + use.holder().getCardName() + " para reducir en " + use.modifier().reduceNow(room, player.getUserId()) + " el coste");
            }
            reduced += use.modifier().reduceNow(room, player.getUserId());
            markUsed(room, player, use);
        }
        // The answers given for this summon are used up
        if (card != null) room.getUsedThisTurn().removeIf(k -> k.startsWith("cost-tap") && k.endsWith("|" + card.getInstanceId()));
        // A cost does not go below 0
        return Math.min(reduced, Math.max(0, cost));
    }

    /** The first one that asks the player for its payment (Flambreik's tributes), or null. */
    static Use choice(GameRoom room, PlayerState player, Event event, String kind, CardSlot card, CardSlot summoner) {
        return applicable(room, player, event, kind, card, summoner).stream()
                .filter(u -> u.modifier().payment().needsChoice()).findFirst().orElse(null);
    }

    private static List<CardSlot> tributesFor(PlayerState player, Payment payment, CardSlot card) {
        return player.getTributeZone().stream().filter(c -> c != card && payment.filter().matches(c)).toList();
    }

    /** The cards that can pay it ({@code card}: the one whose cost it lowers, left out). */
    static List<CardSlot> paymentOptions(PlayerState player, Use use, CardSlot card) {
        Payment payment = use.modifier().payment();
        if (payment.kind() == PaymentKind.TAP_CARD) return player.getField().stream().filter(c -> !c.isTapped() && payment.filter().matches(c)).toList();
        // Each life of the top it may take, one per time
        if (payment.kind() == PaymentKind.SELF_DAMAGE) return player.getLifeStack().subList(0, Math.min(payment.count(), player.getLifeStack().size()));
        return payment.needsChoice() ? tributesFor(player, payment, card) : List.of();
    }

    /** What is asked, for the choice. */
    static String describe(Use use) {
        Payment payment = use.modifier().payment();
        if (payment.kind() == PaymentKind.TRIBUTE_AS_RESOURCE) {
            return "Puedes añadir 1 de tus tributos " + payment.filter().describe() + " como recurso de " + use.holder().getCardName()
                    + " antes de la invocación. Elige 1, o ninguna.";
        }
        if (payment.kind() == PaymentKind.SELF_DAMAGE) {
            return "Hasta " + payment.count() + " veces: puedes infligirte " + payment.damage() + " de daño directo para reducir en " + use.modifier().reduce()
                    + " el coste especial. Elige tantas vidas del tope como veces, o ninguna.";
        }
        if (payment.kind() == PaymentKind.TAP_CARD) {
            return "Puedes agotar 1 carta " + payment.filter().describe() + " bajo tu control para reducir en " + use.modifier().reduce()
                    + " el coste especial. Elige 1, o ninguna.";
        }
        String what = payment.kind() == PaymentKind.TRIBUTES_TO_DECK_BOTTOM
                ? "enviar " + payment.count() + " de tus tributos " + payment.filter().describe() + " al fondo de tu mazo" : "pagarlo";
        return "Puedes " + what + " para reducir en " + use.modifier().reduce() + " el coste. Elige " + payment.count()
                + ", o ninguna para pagar el coste completo.";
    }
}
