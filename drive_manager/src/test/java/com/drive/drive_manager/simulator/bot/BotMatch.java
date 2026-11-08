package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.GameRandom;
import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.simulator.actions.PreRoomActions;
import com.drive.drive_manager.simulator.engine.GameEvent;
import com.drive.drive_manager.simulator.engine.Prompt;
import com.drive.drive_manager.simulator.room.CardMeta;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;
import com.drive.drive_manager.simulator.room.PlayerState;
import com.drive.drive_manager.simulator.room.RoomService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A whole game between two bots with the starter decks (ST1–ST4, three copies of each card, the Iniciador once),
 * without Mongo: each game has a room service of its own, so games can run at the same time on several threads.
 */
public final class BotMatch {

    public static final List<String> DECKS = List.of("st1", "st2", "st3", "st4");
    /** Actions a game may take at most: one that takes more is stuck. */
    public static final int MAX_ACTIONS = 4000;

    /** The starter decks' cards, loaded once. */
    private static final Map<String, List<Card>> CARDS = load();

    private BotMatch() {}

    /**
     * How a game went.
     *
     * @param winner    seat that won ("host", "guest"), null if it did not end
     * @param reason    "lives", "deck" or "surrender"; null if it did not end
     * @param first     seat that took the first turn
     * @param turns     turns played
     * @param refused   moves the engine refused to the bots
     * @param conceded  seat of a bot that found no valid move and conceded, or null
     * @param stuckAt   when no bot had a move in an unfinished game: the status and pending choice
     * @param end       each seat's lives, hand, field and deck at the end
     * @param positions the tables seen during the game by each seat (when asked to keep them), for training a model
     * @param plays     utilities and structures each seat played from its hand
     * @param effects   effects each seat activated (its own effects put in the chain: triggered or by hand)
     */
    record Result(String winner, String reason, String first, int turns, int actions, int refused, String conceded,
                  String stuckAt, long millis, Map<String, Map<String, Integer>> end, List<Position> positions,
                  Map<String, Integer> plays, Map<String, Integer> effects) {

        /** The positions as training samples: each labeled with whether its seat won (none when the game did not end). */
        List<ValueModel.Sample> samples(long game) {
            if (winner == null) return List.of();
            return positions.stream().map(p -> new ValueModel.Sample(p.x(), p.seat().equals(winner) ? 1 : 0, game)).toList();
        }
    }

    /** A table seen by a seat ({@link BoardFeatures}). */
    record Position(String seat, double[] x) {}

    /** Positions are kept at every change of turn and every this many actions. */
    private static final int KEEP_EVERY = 5;

    /** A bot from its description: "passive", "heuristic", or "lookahead" with settings ("lookahead:turns=2,life=12"). */
    static BotPlayer bot(String spec) {
        String[] parts = spec.trim().split(":", 2);
        return switch (parts[0].trim()) {
            case "passive" -> new BotPlayer();
            case "heuristic" -> new HeuristicBot();
            case "lookahead" -> lookahead(parts.length > 1 ? parts[1] : "");
            default -> throw new IllegalArgumentException("Unknown bot: " + spec);
        };
    }

    /** "turns=2,model=path/to/model.json": the settings, and the trained model to score tables with. */
    private static LookaheadBot lookahead(String settings) {
        ValueModel model = null;
        List<String> rest = new ArrayList<>();
        for (String pair : settings.split(",")) {
            String p = pair.trim();
            if (p.startsWith("model=")) {
                try {
                    model = ValueModelFiles.read(java.nio.file.Path.of(p.substring("model=".length())));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else if (!p.isEmpty()) {
                rest.add(p);
            }
        }
        return new LookaheadBot(BotSettings.DEFAULT.with(String.join(",", rest)), model);
    }

    static Result play(String hostDeck, String guestDeck, BotPlayer hostBot, BotPlayer guestBot) {
        return play(hostDeck, guestDeck, hostBot, guestBot, false);
    }

    /** {@code keep}: the positions seen during the game are kept in the result (see {@link Result#positions}). */
    static Result play(String hostDeck, String guestDeck, BotPlayer hostBot, BotPlayer guestBot, boolean keep) {
        return play(hostDeck, guestDeck, hostBot, guestBot, keep, null);
    }

    /** {@code onTurn} sees the room at every change of turn (to measure what happens during games). */
    static Result playWatching(String hostDeck, String guestDeck, BotPlayer hostBot, BotPlayer guestBot,
                               java.util.function.Consumer<GameRoom> onTurn) {
        return play(hostDeck, guestDeck, hostBot, guestBot, false, onTurn);
    }

    /**
     * A game from a seed: the same seed, decks and bots (deciding without a time limit) play the very same game, so two
     * bots can be compared on the same deals (paired games).
     */
    static Result playSeeded(String hostDeck, String guestDeck, BotPlayer hostBot, BotPlayer guestBot, long seed) {
        return play(hostDeck, guestDeck, hostBot, guestBot, false, null, seed);
    }

    /** A game from a seed, watched at every change of turn. */
    static Result playSeededWatching(String hostDeck, String guestDeck, BotPlayer hostBot, BotPlayer guestBot, long seed,
                                     java.util.function.Consumer<GameRoom> onTurn) {
        return play(hostDeck, guestDeck, hostBot, guestBot, false, onTurn, seed);
    }

    private static Result play(String hostDeck, String guestDeck, BotPlayer hostBot, BotPlayer guestBot, boolean keep,
                               java.util.function.Consumer<GameRoom> onTurn) {
        return play(hostDeck, guestDeck, hostBot, guestBot, keep, onTurn, null);
    }

    private static Result play(String hostDeck, String guestDeck, BotPlayer hostBot, BotPlayer guestBot, boolean keep,
                               java.util.function.Consumer<GameRoom> onTurn, Long seed) {
        long start = System.nanoTime();
        RoomService service = new RoomService();
        GameRoom room = service.createRoom("host");
        if (seed != null) {
            // The game's chance, and each bot's own (the copies it tries moves on), all from the seed
            room.setRandom(new GameRandom(seed));
            hostBot.seed(seed * 31 + 1);
            guestBot.seed(seed * 31 + 2);
        }
        String id = room.getRoomId();
        service.applyAction(id, "host", BotPlayer.action("JOIN_ROOM", Map.of("username", "Ana")), false);
        service.applyAction(id, "guest", BotPlayer.action("JOIN_ROOM", Map.of("username", "Beto")), false);
        int[] ids = {0};
        seat(room.getPlayer("host"), hostDeck, ids);
        seat(room.getPlayer("guest"), guestDeck, ids);
        Map<String, BotPlayer> bots = Map.of("host", hostBot, "guest", guestBot);

        int actions = 0;
        int refused = 0;
        String conceded = null;
        String stuckAt = null;
        List<Position> positions = new ArrayList<>();
        String turn = null;
        while (actions < MAX_ACTIONS && room.getStatus() != GameRoom.RoomStatus.POST_GAME) {
            boolean moved = false;
            for (String seat : List.of("host", "guest")) {
                BotPlayer bot = bots.get(seat);
                GameAction action = bot.next(room, seat);
                if (action == null) continue;
                if ("SURRENDER".equals(action.getType())) conceded = seat;
                GameStateUpdate update = service.applyAction(id, seat, action, false);
                actions++;
                moved = true;
                if (update != null && update.getError() != null) {
                    refused++;
                    bot.refused(action, update.getError());
                } else {
                    bot.played(room, action);
                    boolean newTurn = room.getStatus() == GameRoom.RoomStatus.IN_PROGRESS && !room.getCurrentTurnUserId().equals(turn);
                    if (keep && room.getStatus() == GameRoom.RoomStatus.IN_PROGRESS && (newTurn || actions % KEEP_EVERY == 0)) {
                        positions.add(new Position("host", BoardFeatures.of(room, "host")));
                        positions.add(new Position("guest", BoardFeatures.of(room, "guest")));
                    }
                    if (newTurn && onTurn != null) onTurn.accept(room);
                    if (room.getStatus() == GameRoom.RoomStatus.IN_PROGRESS) turn = room.getCurrentTurnUserId();
                }
                break;
            }
            if (!moved) {
                Prompt p = room.getPrompt();
                stuckAt = room.getStatus() + (p == null ? " phase " + room.getCurrentPhase() : " prompt " + p.getPurpose() + " " + p.getTitle());
                break;
            }
        }
        int turns = (int) room.getTimeline().stream().filter(e -> GameEvent.TURN_START.equals(e.getType())).count() + 1;
        Map<String, Integer> plays = new LinkedHashMap<>(Map.of("host", 0, "guest", 0));
        room.getTimeline().stream().filter(e -> GameEvent.PLAY.equals(e.getType()) && plays.containsKey(e.getPlayerId()))
                .forEach(e -> plays.merge(e.getPlayerId(), 1, Integer::sum));
        Map<String, Integer> effects = new LinkedHashMap<>(Map.of("host", 0, "guest", 0));
        room.getTimeline().stream().filter(e -> GameEvent.EFFECT_ACTIVATED.equals(e.getType()) && effects.containsKey(e.getPlayerId()))
                .forEach(e -> effects.merge(e.getPlayerId(), 1, Integer::sum));
        Map<String, Map<String, Integer>> end = new LinkedHashMap<>();
        for (String seat : List.of("host", "guest")) {
            PlayerState p = room.getPlayer(seat);
            Map<String, Integer> zones = new LinkedHashMap<>();
            zones.put("lives", p.getLifeStack().size());
            zones.put("hand", p.getHand().size());
            zones.put("field", p.getField().size());
            zones.put("deck", p.getDeck().size());
            end.put(seat, zones);
        }
        return new Result(room.getWinnerId(), room.getEndReason(), room.getFirstPlayerId(), turns, actions, refused, conceded, stuckAt,
                (System.nanoTime() - start) / 1_000_000, end, positions, plays, effects);
    }

    /** The player's deck as the pre-room leaves it. {@code ids}: the room's counter of instance ids. */
    public static void seat(PlayerState player, String deck, int[] ids) {
        List<CardSlot> slots = new ArrayList<>();
        for (Card meta : CARDS.get(deck)) {
            int copies = Boolean.TRUE.equals(meta.getStarter()) ? 1 : 3;
            for (int i = 0; i < copies; i++) {
                CardSlot s = new CardSlot();
                s.setInstanceId("c" + (++ids[0]));
                s.setCardId(meta.getId());
                s.setFaceDown(true);
                CardMeta.apply(s, meta.getId(), meta);
                slots.add(s);
            }
        }
        player.setDeckId("deck-" + deck);
        player.setDeckName(deck.toUpperCase());
        player.setDeck(slots);
        player.setOriginalDeck(PreRoomActions.deepCopySlots(slots));
        player.setStarterCardId(slots.stream().filter(CardSlot::isStarter).map(CardSlot::getCardId).findFirst().orElse(null));
    }

    /** The deck each card of the starter decks belongs to (lower case names). */
    static Map<String, String> deckOfCards() {
        Map<String, String> out = new LinkedHashMap<>();
        CARDS.forEach((deck, cards) -> cards.forEach(c -> out.putIfAbsent(BotSettings.cardKey(c.getCardName()), deck)));
        return out;
    }

    /** Copies of each card in its deck, as {@link #seat} builds it (the Iniciador once, the rest three times). */
    static Map<String, Integer> copiesOfCards() {
        Map<String, Integer> out = new LinkedHashMap<>();
        CARDS.values().forEach(cards -> cards.forEach(c -> out.putIfAbsent(BotSettings.cardKey(c.getCardName()),
                Boolean.TRUE.equals(c.getStarter()) ? 1 : 3)));
        return out;
    }

    /** The names of the cards of the starter decks (lower case, each once). */
    static List<String> cardNames() {
        return CARDS.values().stream().flatMap(List::stream).map(c -> BotSettings.cardKey(c.getCardName())).distinct().sorted().toList();
    }

    private static Map<String, List<Card>> load() {
        ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        Map<String, List<Card>> decks = new LinkedHashMap<>();
        for (String deck : DECKS) {
            try (InputStream in = BotMatch.class.getResourceAsStream("/simulator/" + deck + "-cards.json")) {
                decks.put(deck, json.readValue(in, new TypeReference<>() {}));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return decks;
    }
}
