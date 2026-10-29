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
    public enum Event { SPECIAL_SUMMON, PLAY }

    /**
     * Whose it is: the special summon's summoner ("cuando esta carta fuera a invocar…"), any of the player's cards on the
     * field ("cuando fueras a invocar…", a structure) or the card being played itself ("cuando fueras a jugar esta carta").
     */
    public enum Holder { SUMMONER, FIELD, SELF }

    public enum PaymentKind { NONE, TAP_SELF, TRIBUTES_TO_DECK_BOTTOM }

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

        /** The player has to choose the cards (the others are applied on their own). */
        public boolean needsChoice() {
            return kind == PaymentKind.TRIBUTES_TO_DECK_BOTTOM;
        }
    }

    /**
     * @param label     how it is printed ("Turno Aliado", "Todos Los Turnos"), for the choice
     * @param kinds     the special summon methods it applies to ("evolution"…); empty: all of them
     * @param allyTurn  only on its controller's turn
     * @param condition the rest of its condition ("si tienes 5 o más tributos [dragón]"), on its holder
     * @param reduce    how much it lowers the cost
     */
    public record CostModifier(String label, Event event, Set<String> kinds, Holder holder, boolean allyTurn,
                               Condition condition, Payment payment, int reduce) {}

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
        if (event == Event.PLAY) holders.add(card);
        holders.addAll(player.getField());
        for (CardSlot holder : holders) {
            // A card face down on the field has no effects (the one being played is not on the field yet)
            if (holder != card && holder.isFaceDown()) continue;
            for (CostModifier m : of(holder)) {
                if (m.event() != event || (!m.kinds().isEmpty() && !m.kinds().contains(kind))) continue;
                boolean placed = switch (m.holder()) {
                    case SELF -> holder == card;
                    case SUMMONER -> summoner == null ? holder != card : holder == summoner;
                    case FIELD -> holder != card;
                };
                if (!placed) continue;
                if (m.allyTurn() && !player.getUserId().equals(room.getCurrentTurnUserId())) continue;
                if (m.condition() != null && !m.condition().test(room, null, holder, player.getUserId())) continue;
                if (!payable(player, m.payment(), holder)) continue;
                uses.add(new Use(m, holder));
            }
        }
        return uses;
    }

    private static boolean payable(PlayerState player, Payment payment, CardSlot holder) {
        return switch (payment.kind()) {
            case NONE -> true;
            case TAP_SELF -> !holder.isTapped();
            case TRIBUTES_TO_DECK_BOTTOM -> player.getTributeZone().stream().filter(payment.filter()::matches).count() >= payment.count();
        };
    }

    /** How much the cost could go down now, all of them used (to tell whether it is affordable). */
    static int available(GameRoom room, PlayerState player, Event event, String kind, CardSlot card, CardSlot summoner) {
        return applicable(room, player, event, kind, card, summoner).stream().mapToInt(u -> u.modifier().reduce()).sum();
    }

    /**
     * Applies those that need no choice while the cost is above 0: a free one (Duregón's) and an exhaustion (a structure,
     * "puedes agotar": always worth it, so it is not asked). Returns the reduction.
     */
    static int applyAutomatic(GameRoom room, PlayerState player, Event event, String kind, CardSlot card, CardSlot summoner, int cost) {
        int reduced = 0;
        for (Use use : applicable(room, player, event, kind, card, summoner)) {
            if (cost - reduced <= 0) break;
            if (use.modifier().payment().needsChoice()) continue;
            if (use.modifier().payment().kind() == PaymentKind.TAP_SELF) {
                use.holder().setTapped(true);
                EffectEngine.record(room, GameEvent.TAP, player.getUserId(), use.holder(), null, null, null, null,
                        player.getUsername() + " agota " + use.holder().getCardName() + " para reducir en " + use.modifier().reduce() + " el coste");
            }
            reduced += use.modifier().reduce();
        }
        return reduced;
    }

    /** The first one that asks the player for its payment (Flambreik's tributes), or null. */
    static Use choice(GameRoom room, PlayerState player, Event event, String kind, CardSlot card, CardSlot summoner) {
        return applicable(room, player, event, kind, card, summoner).stream()
                .filter(u -> u.modifier().payment().needsChoice()).findFirst().orElse(null);
    }

    /** The cards that can pay it. */
    static List<CardSlot> paymentOptions(PlayerState player, Use use) {
        Payment payment = use.modifier().payment();
        return payment.kind() == PaymentKind.TRIBUTES_TO_DECK_BOTTOM
                ? player.getTributeZone().stream().filter(payment.filter()::matches).toList() : List.of();
    }

    /** What is asked, for the choice. */
    static String describe(Use use) {
        Payment payment = use.modifier().payment();
        String what = payment.kind() == PaymentKind.TRIBUTES_TO_DECK_BOTTOM
                ? "enviar " + payment.count() + " de tus tributos " + payment.filter().describe() + " al fondo de tu mazo" : "pagarlo";
        return "Puedes " + what + " para reducir en " + use.modifier().reduce() + " el coste. Elige " + payment.count()
                + ", o ninguna para pagar el coste completo.";
    }
}
