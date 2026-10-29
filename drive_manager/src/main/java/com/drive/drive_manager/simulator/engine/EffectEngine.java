package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.FloatingEffect;
import com.drive.drive_manager.simulator.GameLogEntry;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.EffectScript.Limit;
import com.drive.drive_manager.simulator.engine.EffectStep.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.drive.drive_manager.simulator.engine.Summons.*;
import static com.drive.drive_manager.simulator.engine.Removals.*;
import static com.drive.drive_manager.simulator.engine.TurnRules.*;
import static com.drive.drive_manager.simulator.engine.CombatRules.*;
import static com.drive.drive_manager.simulator.engine.Chain.*;
import static com.drive.drive_manager.simulator.engine.Steps.*;
import static com.drive.drive_manager.simulator.engine.Answers.*;

/**
 * Rules engine of the simulator (Reglamento v1.1). Grows card by card: actions it knows are carried out by
 * the rules; everything else is still played by hand on the board.
 *
 * Flow: an action produces timeline events → once no effect is in the middle of its resolution, those events
 * trigger effects (rule 11.6.0.4) → triggered effects are activated into the chain in rules order (optional
 * ones ask first) → the chain resolves last-in first-out, step by step; a step that needs a choice pauses
 * with a {@link Prompt} until the player answers. Events of a resolution open a new window when it ends.
 * A combat (rule 10) goes on step by step whenever the chain is empty.
 *
 * <p>By theme: {@link Summons} (normal and special summons, playing cards), {@link Removals} (destruction, sacrifice,
 * replacements), {@link CombatRules}, {@link TurnRules}, {@link Chain} (triggers, chain, priority, slow effects),
 * {@link Steps} (each effect step), {@link Answers} (the players' answers), {@link CostModifiers} and {@link HistoryDescriber}.
 * This class keeps the timeline and the helpers they share.
 */
public final class EffectEngine {

    static final int TIMELINE_LIMIT = 2000;
    static final int LOG_LIMIT = 300;

    /** Why a card was destroyed (rule 12.0.0.1). MANUAL: done from the board, outside the engine's rules. */
    public static final String CAUSE_EFFECT = "EFFECT";
    public static final String CAUSE_BATTLE = "BATTLE";
    public static final String CAUSE_RITUAL = "RITUAL";
    public static final String CAUSE_MANUAL = "MANUAL";

    static final String PURPOSE_BLOCK = "BLOCK";
    static final String PURPOSE_PROVOKE = "PROVOKE";
    static final String PURPOSE_ADVANCE = "ADVANCE";
    static final String PURPOSE_RITUAL = "RITUAL";
    /** Evolución, Ascenso and Materialización from the hand: the summoner, then (Materialización) the material. */
    static final String PURPOSE_SPECIAL = "SPECIAL";
    /** Key in the room's per-turn usage: the player already played a structure normally this turn. */
    static final String STRUCTURE_PLAYED = "structure-played|";
    static final String PURPOSE_SPECIAL_FRONT = "SPECIAL_FRONT";
    /** Flambreik: "cuando esta carta fuera a invocar por Evolución: puedes enviar 5 de tus tributos [dragón]…". */
    static final String PURPOSE_SUMMONER_DISCOUNT = "SUMMONER_DISCOUNT";
    static final String PURPOSE_REINFORCE = "REINFORCE";
    static final String PURPOSE_LIFE_PLAY = "LIFE_PLAY";
    /** Turn-scoped marks in usedThisTurn: a Definitive effect used, Última Línea's extra hit, the turn to end. */
    public static final String DEFINITIVE_USED = "definitive";
    static final String EXTRA_HIT = "extra-hit|";
    public static final String END_TURN_REQUESTED = "end-turn|";
    static final String PURPOSE_ORDER = "ORDER";
    /** A card about to leave the field: its owner may replace it ("en su lugar"), or a card that pays it (Caballero Leal's hand card). */
    static final String PURPOSE_REPLACE = "REPLACE";
    static final String PURPOSE_REPLACE_HAND = "REPLACE_HAND";
    static final String PURPOSE_OPTIONAL = "OPTIONAL";
    static final String PURPOSE_PRIORITY = "PRIORITY";
    /** Gained by a creature that cannot declare an attack this turn. */
    static final String NO_ATTACK = "No puede atacar";
    /** "no puede agotarse": it cannot be exhausted (by effects, nor to declare an attack). */
    static final String CANNOT_TAP = "No puede agotarse";
    /** "no puede ser objetivo de efectos de cartas oponentes [nivel N o menor]": "Inmune" or "Inmune N". */
    static final String IMMUNE = "Inmune";
    /** "no puede ser removido por efectos oponentes". */
    static final String UNREMOVABLE = "Irremovible por efectos oponentes";
    static final String PURPOSE_REPLACE_MATERIALS = "REPLACE_MATERIALS";
    /** Marks in usedThisTurn: a Definitive effect used the turn before ("definitive-last|player"). */
    public static final String DEFINITIVE_LAST_TURN = "definitive-last|";
    static final String PURPOSE_BLOCK_SWAP = "BLOCK_SWAP";
    static final String PURPOSE_ASCEND_ATTACK = "ASCEND_ATTACK";
    static final String PURPOSE_DAMAGE_REPLACE = "DAMAGE_REPLACE";
    /** An "until the end of the attack" entry that is strength, not an ability ("id|fuerza:3"). */
    static final String STRENGTH_ENTRY = "fuerza:";

    private EffectEngine() {}

    // ------------------------------------------------------------------ Timeline

    /**
     * Adds an event to the timeline (and to the events waiting to trigger effects) and, when {@code logText}
     * is given, a line to the text log.
     */
    static GameEvent record(GameRoom room, String type, String playerId, CardSlot card, String from, String to,
                            PendingEffect effect, Map<String, Object> data, String logText) {
        GameEvent e = new GameEvent();
        e.setSeq(room.getEventSeq() + 1);
        room.setEventSeq(e.getSeq());
        e.setTs(System.currentTimeMillis());
        e.setType(type);
        e.setPlayerId(playerId);
        if (card != null) {
            e.setCardInstanceId(card.getInstanceId());
            e.setCardName(card.getCardName());
            // Entering the field (summoned, played…): it reacts to events from this one on
            if ("field".equals(to)) card.setFieldSeq(e.getSeq());
        }
        e.setFromZone(from);
        e.setToZone(to);
        if (effect != null) {
            e.setEffectId(effect.getId());
            e.setResolutionId(effect.getResolutionId());
            // A tribute by an effect always says by which card ("si esta carta es tributada por efecto de 1 [bestia]")
            if (GameEvent.TRIBUTE.equals(type) && (data == null || !data.containsKey("byCard")) && effect.getSourceInstanceId() != null) {
                data = data == null ? new HashMap<>() : new HashMap<>(data);
                data.put("byCard", effect.getSourceInstanceId());
            }
        }
        e.setData(data);
        room.getTimeline().add(e);
        if (room.getTimeline().size() > TIMELINE_LIMIT) room.getTimeline().remove(0);
        room.getPendingEvents().add(e);
        refreshContinuous(room);
        if (logText != null) log(room, logText, e.getSeq());
        return e;
    }

    static void log(GameRoom room, String text, Long seq) {
        GameLogEntry entry = new GameLogEntry(System.currentTimeMillis(), text, seq);
        HistoryDescriber.describe(room, entry);
        room.getEventLog().add(entry);
        if (room.getEventLog().size() > LOG_LIMIT) room.getEventLog().remove(0);
    }

    /**
     * Rule 2.1 (victory): a player loses when their life stack reaches 0 or when they cannot make the regulatory draw at
     * the start of their turn. Those are the only two ways: an empty deck at any other moment is not a loss.
     */
    public static void loseByDeckOut(GameRoom room, String playerId) {
        PlayerState loser = room.getPlayer(playerId);
        if (loser != null) endGame(room, loser, "deck", loser.getUsername() + " no puede robar al inicio de su turno: pierde la partida");
    }

    static void loseByLives(GameRoom room, PlayerState loser) {
        endGame(room, loser, "lives", loser.getUsername() + " se quedó sin vidas: pierde la partida");
    }

    /** The game ends at once: nothing pending goes on (combat, chain, triggers), and the room goes to the post-game. */
    static void endGame(GameRoom room, PlayerState loser, String reason, String text) {
        record(room, GameEvent.GAME_LOST, loser.getUserId(), null, null, null, null, Map.of("reason", reason), text);
        room.setWinnerId(room.getOpponentId(loser.getUserId()));
        room.setEndReason(reason);
        room.setCombat(null);
        room.getChain().clear();
        room.getTriggerQueue().clear();
        room.getPendingEvents().clear();
        room.getPostGameChoices().clear();
        room.setStatus(GameRoom.RoomStatus.POST_GAME);
    }

    /** The event that triggered an effect (null for one activated by its player). */
    static GameEvent triggerEvent(GameRoom room, PendingEffect effect) {
        return eventAt(room, effect.getTriggerSeq());
    }

    /** A timeline event by its seq: the timeline is ordered by seq, so it is a binary search. */
    static GameEvent eventAt(GameRoom room, Long seq) {
        if (seq == null) return null;
        List<GameEvent> timeline = room.getTimeline();
        int lo = 0;
        int hi = timeline.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long at = timeline.get(mid).getSeq();
            if (at == seq) return timeline.get(mid);
            if (at < seq) lo = mid + 1;
            else hi = mid - 1;
        }
        return null;
    }

    /**
     * A card of a player's Línea frontal (rule 4.0.6.2: the Retaguardia cannot be chosen by the opponent's effects), or
     * null when it is no longer there: an effect's target that stopped being valid (rule 11.10.2).
     */
    static CardSlot frontLineCard(PlayerState player, String instanceId) {
        return player == null ? null : find(frontLine(player.getField()), instanceId);
    }

    /** A card anywhere in the room, attached ones included. */
    public static CardSlot findInRoom(GameRoom room, String instanceId) {
        return cardsInRoom(room).filter(c -> instanceId.equals(c.getInstanceId())).findFirst().orElse(null);
    }

    /** Every card of the room, in every zone, with the cards attached to them. */
    static java.util.stream.Stream<CardSlot> cardsInRoom(GameRoom room) {
        return room.getPlayers().values().stream()
                .flatMap(p -> ZONES.stream().flatMap(z -> zone(p, z).stream()))
                .flatMap(c -> java.util.stream.Stream.concat(java.util.stream.Stream.of(c),
                        java.util.stream.Stream.concat(c.getMaterials().stream(), c.getResources().stream())));
    }

    // ------------------------------------------------------------------ Helpers

    static EffectScript script(PendingEffect effect) {
        return CardEffects.byId(effect.getScriptId())
                .orElseThrow(() -> new IllegalStateException("Unknown effect " + effect.getScriptId()));
    }

    /** The card an event is about, where it is now (its zone after the event). */
    public static CardSlot eventCard(GameRoom room, GameEvent event) {
        String owner = ownerOf(event) != null ? ownerOf(event) : event.getPlayerId();
        String zone = event.getToZone() != null ? event.getToZone() : "field";
        return findIn(room, owner, zone, event.getCardInstanceId());
    }

    static String ownerOf(GameEvent event) {
        return event.getData() == null ? null : (String) event.getData().get("ownerId");
    }

    public static int strength(CardSlot card) {
        return (card.getStrength() == null ? 0 : card.getStrength()) + card.getStrengthModifier() + card.getTurnStrength()
                + card.getLaterStrength() + card.getContinuousStrength();
    }

    /** Its abilities: printed, inherited from its Recursos, gained this turn and given by its continuous effects now. */
    public static List<String> keywords(CardSlot card) {
        List<String> all = new ArrayList<>(card.getKeywords());
        for (CardSlot resource : card.getResources()) all.addAll(resource.getInheritKeywords());
        all.addAll(card.getTurnKeywords());
        all.addAll(card.getLaterKeywords());
        all.addAll(card.getContinuousKeywords());
        return all;
    }

    /**
     * Continuous effects with a condition ("mientras controles 3 o más aliados Rojos") are checked again after every
     * event: each card on the field gets what those that hold give it, and loses what those that stopped gave it.
     */
    public static void refreshContinuous(GameRoom room) {
        for (PlayerState p : room.getPlayers().values()) {
            // What the cards' auras give their allies ("todos los aliados [armada] ganan fuerza 1")
            Map<String, Integer> aura = new HashMap<>();
            for (CardSlot source : p.getField()) {
                if (source.isFaceDown()) continue;
                for (CardEffects.Aura a : CardEffects.aurasOf(source)) {
                    if (a.allyTurn() && !p.getUserId().equals(room.getCurrentTurnUserId())) continue;
                    if (a.condition() != null && !a.condition().test(room, null, source, p.getUserId())) continue;
                    for (CardSlot ally : p.getField()) if (a.targets().matches(ally)) aura.merge(ally.getInstanceId(), a.strength(), Integer::sum);
                }
            }
            for (CardSlot card : p.getField()) {
                int strength = aura.getOrDefault(card.getInstanceId(), 0);
                int level = 0;
                List<String> keywords = new ArrayList<>();
                for (CardEffects.Continuous c : CardEffects.continuousOf(card)) {
                    if (!"field".equals(c.zone())) continue;
                    if (c.allyTurn() && !p.getUserId().equals(room.getCurrentTurnUserId())) continue;
                    if (card.isFaceDown() || !c.condition().test(room, null, card, p.getUserId())) continue;
                    strength += c.strength() + (c.bonus() == null ? 0 : c.bonus().of(room, p.getUserId()));
                    level += c.levelBonus() == null ? 0 : c.levelBonus().of(room, p.getUserId());
                    keywords.addAll(c.keywords());
                }
                card.setContinuousStrength(strength);
                card.setContinuousLevel(level);
                card.setContinuousKeywords(keywords);
            }
        }
    }

    record Located(CardSlot card, String zone) {}

    static final List<String> ZONES = List.of("field", "hand", "tributeZone", "discardPile", "deck", "lifeStack");

    static Located locate(PlayerState player, String instanceId) {
        for (String z : ZONES) {
            CardSlot card = find(zone(player, z), instanceId);
            if (card != null) return new Located(card, z);
        }
        return null;
    }

    static CardSlot findAnywhere(GameRoom room, String playerId, String instanceId) {
        PlayerState p = room.getPlayer(playerId);
        Located at = p == null ? null : locate(p, instanceId);
        return at == null ? null : at.card();
    }

    static List<CardSlot> zone(PlayerState player, String zone) {
        return switch (zone) {
            case "field" -> player.getField();
            case "hand" -> player.getHand();
            case "tributeZone" -> player.getTributeZone();
            case "discardPile" -> player.getDiscardPile();
            case "deck" -> player.getDeck();
            case "lifeStack" -> player.getLifeStack();
            default -> List.of();
        };
    }

    static String zoneName(String zone) {
        return switch (zone) {
            case "tributeZone" -> "tributos";
            case "discardPile" -> "descarte";
            case "hand" -> "mano";
            case "deck" -> "mazo";
            case "field" -> "campo";
            default -> zone;
        };
    }

    static List<String> ids(List<CardSlot> cards) {
        return cards.stream().map(CardSlot::getInstanceId).toList();
    }

    static CardSlot find(List<CardSlot> cards, String instanceId) {
        if (instanceId == null) return null;
        return cards.stream().filter(c -> instanceId.equals(c.getInstanceId())).findFirst().orElse(null);
    }

    static CardSlot findIn(GameRoom room, String playerId, String zone, String instanceId) {
        PlayerState p = playerId == null ? null : room.getPlayer(playerId);
        return p == null || zone == null ? null : find(zone(p, zone), instanceId);
    }

    static List<CardSlot> concat(List<CardSlot> a, List<CardSlot> b) {
        List<CardSlot> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    static List<String> concatStrings(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a == null ? List.of() : a);
        if (b != null) all.addAll(b);
        return all;
    }

    static String name(CardSlot card) {
        return card.getCardName() != null ? card.getCardName() : "una carta";
    }

    /** The cards moved, for the history when they were public (": A, B y C"); nothing when there are none. */
    static String names(List<CardSlot> cards) {
        List<String> names = cards.stream().map(EffectEngine::name).toList();
        if (names.isEmpty()) return "";
        if (names.size() == 1) return ": " + names.get(0);
        return ": " + String.join(", ", names.subList(0, names.size() - 1)) + " y " + names.get(names.size() - 1);
    }

    /** Zones whose cards everybody sees (what leaves them can be named). */
    static boolean publicZone(String zone) {
        return "tributeZone".equals(zone) || "discardPile".equals(zone) || "field".equals(zone);
    }

    static String plural(int n, String one, String many) {
        return n == 1 ? one : many;
    }

    static String gemsText(int n) {
        return n + " " + plural(n, "gema", "gemas");
    }

    static int clamp(int value) {
        return Math.max(-10, Math.min(10, value));
    }

    public static boolean inFrontLine(CardSlot card) {
        return card.getY() != null && card.getY() < 50;
    }

    static List<CardSlot> frontLine(List<CardSlot> field) {
        return field.stream().filter(EffectEngine::inFrontLine).toList();
    }

    /** The player's cards on the field (Línea frontal and Retaguardia) that match. */
    static long countOnField(GameRoom room, String playerId, CardFilter filter) {
        PlayerState player = room.getPlayer(playerId);
        return player == null ? 0 : player.getField().stream().filter(filter::matches).count();
    }

    /** The player's cards in "tributeZone" or "hand" that match. */
    static long countInZone(GameRoom room, String playerId, String zone, CardFilter filter) {
        PlayerState player = room.getPlayer(playerId);
        if (player == null) return 0;
        List<CardSlot> cards = "tributeZone".equals(zone) ? player.getTributeZone() : "hand".equals(zone) ? player.getHand() : List.of();
        return cards.stream().filter(filter::matches).count();
    }

    /** The sum of the levels of the player's creatures on the field that match. */
    static int levelsOnField(GameRoom room, String playerId, CardFilter filter) {
        PlayerState player = room.getPlayer(playerId);
        return player == null ? 0 : player.getField().stream().filter(c -> "creature".equals(c.getCardType()) && filter.matches(c))
                .mapToInt(c -> c.getLevel() == null ? 0 : c.getLevel()).sum();
    }

    /** The card cannot be targeted by an effect of an opponent's card of that level ("Inmune", "Inmune 4"). */
    static boolean immuneTo(CardSlot card, int sourceLevel) {
        for (String k : keywords(card)) {
            if (!k.startsWith(IMMUNE)) continue;
            Matcher m = Pattern.compile("(\\d+)").matcher(k);
            if (!m.find() || sourceLevel <= Integer.parseInt(m.group(1))) return true;
        }
        return false;
    }

    /** How many lives the player has. */
    static int livesOf(GameRoom room, String playerId) {
        PlayerState player = room.getPlayer(playerId);
        return player == null ? 0 : player.getLifeStack().size();
    }

    /** Gems the player has on the shared Contador (rule 4.0.9): positive on their side. */
    public static int gemsOf(GameRoom room, String userId) {
        return room.getSharedCounter() * direction(room, userId);
    }

    /** Same convention as the room: the first player counts the Contador negatively. */
    static int direction(GameRoom room, String userId) {
        String firstId = room.getPlayers().keySet().iterator().next();
        return firstId.equals(userId) ? -1 : 1;
    }

    /**
     * Rule 5.0.0.1: on your turn, paying may not leave the opponent with more gems than their Marcador shows.
     * On the opponent's turn that limit does not apply: only the Contador's end (10 gems for the opponent).
     * Paying nothing does not move the Contador, so a cost of 0 can always be paid.
     */
    static boolean canPay(GameRoom room, String userId, int cost) {
        if (cost <= 0) return true;
        if (!userId.equals(room.getCurrentTurnUserId())) return gemsOf(room, userId) - cost >= -10;
        PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
        int opponentMarker = opponent == null ? 0 : opponent.getResourceCount();
        return gemsOf(room, userId) - cost >= -opponentMarker;
    }

    static void pay(GameRoom room, String userId, int cost) {
        room.setSharedCounter(clamp(room.getSharedCounter() - cost * direction(room, userId)));
    }

    static void rulesPrompt(GameRoom room, String playerId, String purpose, String title, String instruction,
                                     String chosenLabel, List<CardSlot> options, List<CardSlot> shown) {
        Prompt p = new Prompt();
        p.setId(room.nextId("prompt"));
        p.setKind(Prompt.Kind.PICK);
        p.setPurpose(purpose);
        p.setPlayerId(playerId);
        p.setSourceName(title);
        p.setTitle(title);
        p.setInstruction(instruction);
        p.setChosenLabel(chosenLabel);
        p.setCards(new ArrayList<>(shown));
        p.setValidIds(new ArrayList<>(ids(options)));
        p.setMin(0);
        p.setMax(1);
        room.setPrompt(p);
    }

    /**
     * The value of a keyword the card has, printed or gained this turn: 0 when it has no number ("Provocar"),
     * null when it does not have it.
     */
    static Integer keywordLevel(CardSlot card, String keyword) {
        Integer best = null;
        for (String k : keywords(card)) {
            if (!k.toLowerCase(Locale.ROOT).startsWith(keyword.toLowerCase(Locale.ROOT))) continue;
            Matcher m = Pattern.compile("(\\d+)").matcher(k);
            int value = m.find() ? Integer.parseInt(m.group(1)) : 0;
            best = best == null ? value : Math.max(best, value);
        }
        return best;
    }

    /**
     * Whether a card can be reactivated: an exhausted creature. Effects that forbid reactivating a creature
     * will be checked here once a card has one.
     */
    static boolean reactivable(CardSlot card) {
        return "creature".equals(card.getCardType()) && card.isTapped() && card.getNoUntap() != 1;
    }

    static List<CardSlot> allies(PlayerState player) {
        return player.getField().stream().filter(c -> "creature".equals(c.getCardType())).toList();
    }

    /** Notes what an effect gave or took from a card for a while, for its details. */
    static void floating(CardSlot card, PendingEffect effect, String text, String until, int strength, String keyword) {
        card.getFloating().add(new FloatingEffect(text, effect == null ? null : effect.getSourceName(), until, strength, keyword));
    }
}
