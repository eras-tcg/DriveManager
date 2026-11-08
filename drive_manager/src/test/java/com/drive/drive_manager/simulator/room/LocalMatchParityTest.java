package com.drive.drive_manager.simulator.room;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.simulator.bot.BotPlayer;
import com.drive.drive_manager.simulator.bot.HeuristicBot;
import com.drive.drive_manager.simulator.engine.Chain;
import com.drive.drive_manager.simulator.engine.Combat;
import com.drive.drive_manager.simulator.engine.GameEvent;
import com.drive.drive_manager.simulator.engine.GameRandom;
import com.drive.drive_manager.simulator.engine.PendingEffect;
import com.drive.drive_manager.simulator.engine.Prompt;
import com.drive.drive_manager.simulator.platform.Clocks;
import com.eras.engine.local.LocalMatch;
import com.eras.engine.local.UpdateJson;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import kotlin.jvm.functions.Function0;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The game in the browser ({@link LocalMatch}, eras-engine's common code) sends its player exactly what the server sends
 * them: the same seeded game is played through the server's real path (the STOMP controller {@link GameController},
 * {@link RoomService}, {@code RoomEngine}/{@code ActionDispatcher}, the bot moved by {@code BotRooms.playNow}, every
 * message to the player's queue as {@link RoomMessages} sends it, written by Spring Boot's own JSON mapper) and through
 * a LocalMatch, with the same bodies; every message must be the same text, byte for byte (and the same JSON tree).
 * <p>
 * The player's actions are chosen by a seeded heuristic bot on the server's room and sent as the board's JSON; the room
 * against the bot plays the server's bot (LookaheadBot at its level, the server's bot resources) with its chance seeded
 * and its time counted in clock readings ({@link Clocks#ticks}) on both sides, so the two games are the same. What the
 * server makes up at random is made predictable here as LocalMatch makes it: the room's id (LocalMatch is given the
 * server's), the card instances' ids ({@link DeckLoader#instanceIds}: "c1", "c2"…), the game's chance (the room's random
 * from the seed), the history's times (one fixed time).
 */
class LocalMatchParityTest {

    private static final String USER = LocalMatch.DEFAULT_USER;
    private static final long TICK_NANOS = 20_000_000L;
    private static final List<String> DECKS = List.of("st1", "st2", "st3", "st4");
    /** The starter decks' metadata (as BotMatch reads them), and as LocalMatch's decks. */
    private static final Map<String, List<Card>> CARDS = load();
    private static final String DECKS_JSON = decksJson();
    /** Spring Boot's JSON mapper, as the application builds it (its Jackson auto-configuration, the @JacksonMixin scan). */
    private static final JsonMapper BOOT = bootMapper();

    /** Per game: messages compared, the actions sent and refused. */
    private final List<String> report = new ArrayList<>();

    @AfterEach
    void restoreIds() {
        DeckLoader.instanceIds = () -> java.util.UUID.randomUUID().toString();
        report.forEach(System.out::println);
    }

    @Test
    void theWriterHasJacksonsPropertiesInJacksonsOrder() {
        Map<String, Object> samples = new LinkedHashMap<>();
        samples.put("GameStateUpdate", new GameStateUpdate());
        samples.put("PlayerState", new PlayerState(null, null));
        samples.put("CardSlot", new CardSlot());
        samples.put("FloatingEffect", new FloatingEffect(null, null, null, 0, null));
        samples.put("GameLogEntry", new GameLogEntry());
        samples.put("GameEvent", new GameEvent());
        samples.put("PendingEffect", new PendingEffect());
        samples.put("Prompt", new Prompt());
        samples.put("OfferedEffect", new Prompt.OfferedEffect(null, null, null, null, null, null, null));
        samples.put("Combat", new Combat());
        samples.put("Activatable", new Chain.Activatable(null, null, null, null));
        Map<String, List<String>> jackson = new LinkedHashMap<>();
        samples.forEach((name, sample) -> {
            List<String> names = new ArrayList<>();
            BOOT.valueToTree(sample).propertyNames().forEach(names::add);
            jackson.put(name, names);
        });
        assertThat(UpdateJson.INSTANCE.getPROPERTIES()).isEqualTo(jackson);
    }

    @Test
    void theStarterMirrorAgainstTheNormalBot() {
        play(new Game("st1", null, 1, null, List.of(), -1));
    }

    @Test
    void aChosenDeckForTheBotAndRefusedActions() {
        // Before choosing a deck: an action of the game (refused: not started), an unknown one, a level that does not exist,
        // a body Jackson cannot read (dropped: nothing sent), the bot's pace (nothing sent; then in every update)
        play(new Game("st2", "st3", 2, "easy", List.of(
                body("END_TURN", Map.of()), body("NOPE", Map.of()), body("SET_BOT_LEVEL", Map.of("level", "imposible")),
                "{\"type\":\"END_TURN\",\"payload\":[1]}", body("SET_BOT_DELAY", Map.of("ms", 250))), 25));
    }

    @Test
    void st4AgainstSt1() {
        play(new Game("st4", "st1", 3, "easy", List.of(), 40));
    }

    /** Ranked: the bot at a new player's strength with a copy of the player's deck (its own instances), who starts drawn. */
    @Test
    void aRankedGameAgainstTheNormalBot() {
        play(new Game("st2", null, 7, null, List.of(), -1, List.of(body("SET_RANKED", Map.of("ranked", true))), List.of()));
    }

    /** After the game: the player leaves (the room closes) and then acts again (the room no longer exists). */
    @Test
    void st3AgainstSt4ThenLeaving() {
        play(new Game("st3", "st4", 5, "easy", List.of(), -1, List.of(),
                List.of(body("LEAVE_ROOM", Map.of()), body("END_TURN", Map.of()), body("SET_BOT_DELAY", Map.of("ms", 0)))));
    }

    /**
     * A game: `extras` are sent after joining (before the deck), `afterDeck` once the decks are chosen, `illegalAt`: after
     * that many of the player's actions, an attack with a card that does not exist (refused, an error to the player alone),
     * `afterGame` once the game is over and the rematch chosen.
     */
    record Game(String deck, String botDeck, long seed, String level, List<String> extras, int illegalAt, List<String> afterDeck,
                List<String> afterGame) {
        Game(String deck, String botDeck, long seed, String level, List<String> extras, int illegalAt) {
            this(deck, botDeck, seed, level, extras, illegalAt, List.of(), List.of());
        }
    }

    private void play(Game game) {
        // The server: its controller and room service, the messages to the player's queue written by Boot's mapper
        Capture template = new Capture();
        RoomService service = new RoomService();
        ReflectionTestUtils.setField(service, "messagingTemplate", template);
        service.decks = new StarterDecks();
        GameController controller = new GameController();
        ReflectionTestUtils.setField(controller, "roomService", service);
        int[] ids = {0};
        DeckLoader.instanceIds = () -> "c" + (++ids[0]);
        // The server's side on a thread of its own (as the server's bot thinks on its own threads), the player's decisions on
        // another: what each keeps per thread (the bot's memory of verdicts) starts empty and is theirs alone
        ExecutorService serverThread = Executors.newSingleThreadExecutor();
        ExecutorService playerThread = Executors.newSingleThreadExecutor();
        try {
            play(game, template, service, controller, serverThread, playerThread);
        } finally {
            serverThread.shutdownNow();
            playerThread.shutdownNow();
        }
    }

    private void play(Game game, Capture template, RoomService service, GameController controller, ExecutorService serverThread,
                      ExecutorService playerThread) {
        Function0<Long> serverTicks = Clocks.INSTANCE.ticks(TICK_NANOS);
        Function0<Long> playerTicks = Clocks.INSTANCE.ticks(TICK_NANOS);
        GameRoom room = on(serverThread, serverTicks, () -> service.createRoom(USER, RoomOptions.BOT));
        room.setRandom(new GameRandom(game.seed()));
        String id = room.getRoomId();
        // The browser: the same room id, seed, decks, fixed time and bot clock
        LocalMatch local = new LocalMatch("{\"seed\":\"" + game.seed() + "\",\"roomId\":\"" + id + "\",\"time\":0,\"botClock\":\"ticks\","
                + "\"tickNanos\":\"" + TICK_NANOS + "\",\"decks\":" + DECKS_JSON + "}");
        AtomicReference<BotPlayer> seeded = new AtomicReference<>();
        Principal principal = () -> USER;
        int[] compared = {0};
        java.util.function.Function<String, List<String>> send = bodyText -> {
            int from = template.sent.size();
            on(serverThread, serverTicks, () -> {
                GameAction action;
                try {
                    action = BOOT.readValue(bodyText, GameAction.class);
                } catch (RuntimeException unreadable) {
                    return null; // Spring drops a message it cannot convert: nothing is sent
                }
                controller.handleAction(id, action, principal, "session-1");
                // The bot plays as BotRooms does after every action (playNow: right away), seeded as LocalMatch seeds it;
                // a call that only conceded is followed by another (the server's sweep wakes it again)
                while (true) {
                    seedBot(service, id, game.seed(), seeded);
                    int before = template.sent.size();
                    int moves = service.playBotNow(id);
                    if (moves == 0 && template.sent.size() == before) break;
                }
                return null;
            });
            List<String> server = new ArrayList<>(template.sent.subList(from, template.sent.size()));
            List<String> browser = new ArrayList<>(List.of(local.send(bodyText)));
            browser.addAll(List.of(local.playBot()));
            assertThat(local.lastError()).as("LocalMatch failed on %s", bodyText).satisfiesAnyOf(
                    e -> assertThat(e).isNull(), e -> assertThat(e).startsWith("Unreadable action"));
            assertThat(browser.size()).as("messages after %s: server %s, browser %s", bodyText, summary(server), summary(browser))
                    .isEqualTo(server.size());
            for (int i = 0; i < server.size(); i++) {
                assertThat(BOOT.readTree(browser.get(i))).as("message %d after %s (as JSON)", compared[0] + i, bodyText)
                        .isEqualTo(BOOT.readTree(server.get(i)));
                assertThat(browser.get(i)).as("message %d after %s (as text)", compared[0] + i, bodyText).isEqualTo(server.get(i));
            }
            compared[0] += server.size();
            return server;
        };

        send.apply(body("JOIN_ROOM", nullable("deckId", null, "username", "Ana")));
        for (String extra : game.extras()) send.apply(extra);
        if (game.level() != null) send.apply(body("SET_BOT_LEVEL", Map.of("level", game.level())));
        send.apply(body("SET_DECK", Map.of("deckId", "deck-" + game.deck())));
        if (game.botDeck() != null) send.apply(body("SET_DECK", Map.of("deckId", "deck-" + game.botDeck(), "actAs", "bot")));
        for (String extra : game.afterDeck()) send.apply(extra);

        HeuristicBot player = new HeuristicBot();
        player.seed(game.seed() * 31 + 1);
        int actions = 0, refused = 0;
        boolean errorSent = false;
        while (actions < 3000 && room.getStatus() != GameRoom.RoomStatus.POST_GAME) {
            if (actions == game.illegalAt()) {
                List<String> answer = send.apply(body("ATTACK", Map.of("instanceId", "no-such-card")));
                assertThat(answer).singleElement().asString().contains("\"error\":\"").contains("\"actorId\":\"" + USER + "\"");
                errorSent = true;
            }
            GameAction action = on(playerThread, playerTicks, () -> player.next(room, USER));
            if (action == null) break;
            actions++;
            List<String> answer = send.apply(body(action.getType(), action.getPayload() == null ? Map.of() : action.getPayload()));
            tools.jackson.databind.JsonNode errorNode = answer.isEmpty() ? null : BOOT.readTree(answer.get(0)).get("error");
            String error = errorNode != null && errorNode.isString() ? errorNode.asString() : null;
            if (error != null) {
                refused++;
                player.refused(action, error);
            } else {
                player.played(room, action);
            }
        }
        String winner = room.getWinnerId();
        // The end: both choose a rematch (the bot at once), and the next game starts at its mulligan
        if (room.getStatus() == GameRoom.RoomStatus.POST_GAME) send.apply(body("POST_GAME_CHOICE", Map.of("choice", "rematch")));
        for (String extra : game.afterGame()) send.apply(extra);
        report.add(game + ": " + compared[0] + " messages byte-identical, " + actions + " actions (" + refused + " refused), "
                + "winner " + winner + ", then " + room.getStatus());
        assertThat(winner).as("the game ended").isNotNull();
        if (game.illegalAt() >= 0) assertThat(errorSent).isTrue();
    }

    /** Each message's event and who caused it (to tell where two lists part). */
    private static List<String> summary(List<String> messages) {
        return messages.stream().map(m -> {
            tools.jackson.databind.JsonNode n = BOOT.readTree(m);
            return n.path("event").asString("") + "/" + n.path("actorId").asString("") + "/" + n.path("error").asString("");
        }).toList();
    }

    /** The bot of the server's room seeded as LocalMatch seeds each bot it seats (a new one after SET_BOT_LEVEL). */
    @SuppressWarnings("unchecked")
    private static void seedBot(RoomService service, String id, long seed, AtomicReference<BotPlayer> seeded) {
        Object botRooms = service.botRooms;
        BotPlayer bot = ((Map<String, BotPlayer>) ReflectionTestUtils.getField(botRooms, "bots")).get(id);
        if (bot != null && bot != seeded.get()) {
            bot.seed(seed * 31 + 2);
            seeded.set(bot);
        }
    }

    /** Runs on that thread with LocalMatch's clocks: the history's time fixed at 0, the bot's counted in readings. */
    private static <T> T on(ExecutorService thread, Function0<Long> ticks, Supplier<T> block) {
        Function0<Long> zero = () -> 0L;
        try {
            return thread.submit(() -> Clocks.INSTANCE.using(zero, ticks, block::get)).get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            if (e.getCause() instanceof Error r) throw r;
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** The board's body: {"type": …, "payload": {…}}, as JSON.stringify writes it. */
    private static String body(String type, Map<String, ?> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("payload", payload);
        return BOOT.writeValueAsString(body);
    }

    private static Map<String, Object> nullable(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** The messaging template: what the server sends each user, written as Spring's STOMP converter writes it. */
    static final class Capture extends SimpMessagingTemplate {
        final List<String> sent = new ArrayList<>();

        Capture() {
            super(new MessageChannel() {
                @Override
                public boolean send(Message<?> message, long timeout) {
                    return true;
                }
            });
        }

        @Override
        public void convertAndSendToUser(String user, String destination, Object payload) {
            assertThat(user).isEqualTo(USER);
            assertThat(destination).startsWith("/queue/room/");
            sent.add(new String(BOOT.writeValueAsBytes(payload), StandardCharsets.UTF_8));
        }
    }

    /** The decks "deck-st1"…"deck-st4" as DeckLoader would make them from Mongo: the starter decks' metadata (BotMatch). */
    static final class StarterDecks extends DeckLoader {
        StarterDecks() {
            super(null, null, null, "https://example.invalid");
        }

        @Override
        public Optional<DeckList> deck(String deckId) {
            if (!deckId.startsWith("deck-") || !CARDS.containsKey(deckId.substring(5))) return Optional.empty();
            DeckList deck = new DeckList();
            deck.setId(deckId);
            deck.setDeckName(deckId.substring(5).toUpperCase());
            return Optional.of(deck);
        }

        @Override
        public List<CardSlot> slots(DeckList deck) {
            List<CardSlot> slots = new ArrayList<>();
            for (Card meta : CARDS.get(deck.getId().substring(5))) {
                int copies = Boolean.TRUE.equals(meta.getStarter()) ? 1 : 3;
                for (int i = 0; i < copies; i++) {
                    CardSlot s = new CardSlot();
                    s.setInstanceId(instanceIds.get());
                    s.setCardId(meta.getId());
                    s.setFaceDown(true);
                    s.setCardName(meta.getCardName());
                    CardMeta.apply(s, meta.getId(), meta);
                    slots.add(s);
                }
            }
            return slots;
        }

        @Override
        public Card metadataOf(String cardId) {
            return CARDS.values().stream().flatMap(List::stream).filter(c -> cardId.equals(c.getId())).findFirst().orElse(null);
        }
    }

    private static JsonMapper bootMapper() {
        AtomicReference<JsonMapper> mapper = new AtomicReference<>();
        new ApplicationContextRunner()
                .withInitializer(context -> AutoConfigurationPackages.register((BeanDefinitionRegistry) context.getBeanFactory(),
                        "com.drive.drive_manager"))
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> mapper.set(context.getBean(JsonMapper.class)));
        return mapper.get();
    }

    private static Map<String, List<Card>> load() {
        ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        Map<String, List<Card>> decks = new LinkedHashMap<>();
        for (String deck : DECKS) {
            try (InputStream in = LocalMatchParityTest.class.getResourceAsStream("/simulator/" + deck + "-cards.json")) {
                decks.put(deck, json.readValue(in, new TypeReference<>() {}));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return decks;
    }

    private static String decksJson() {
        StringBuilder out = new StringBuilder("{");
        for (String deck : DECKS) {
            try (InputStream in = LocalMatchParityTest.class.getResourceAsStream("/simulator/" + deck + "-cards.json")) {
                if (out.length() > 1) out.append(',');
                out.append("\"deck-").append(deck).append("\":{\"deckName\":\"").append(deck.toUpperCase()).append("\",\"metadata\":")
                        .append(new String(in.readAllBytes(), StandardCharsets.UTF_8).trim()).append('}');
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out.append('}').toString();
    }
}
