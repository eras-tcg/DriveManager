package com.drive.drive_manager.simulator.offline;

import com.drive.drive_manager.service.ProgressionRules;
import com.drive.drive_manager.simulator.mission.MissionEvents;
import com.drive.drive_manager.simulator.mission.MissionHooks;
import com.drive.drive_manager.simulator.replay.MatchHistory;
import com.drive.drive_manager.simulator.replay.MatchRecorder;
import com.drive.drive_manager.simulator.replay.MatchReplay;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.eras.engine.local.LocalMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Games played offline against the bot, counted when the player connects again, exactly as a game against the bot on
 * the server is counted, and only when the server, playing it again, gets the same game:
 * <ol>
 *   <li>its shape is bounded (actions, the bot's moves, each action's size) and its time plausible (started within
 *       {@code windowDays} days, not in the future);</li>
 *   <li>it is new: the same id, or an accepted game with the same seed and game number, answers "duplicate";</li>
 *   <li>the server is not overloaded by this player: games looked at today under {@code dailyReplays} (a generous guard
 *       against a client sending junk; over it the game is "retry", kept by the site and sent again, never lost). There
 *       is no cap on the games accepted: every honest game counts;</li>
 *   <li>every deck it chose is legal for the player, made from the server's data ({@link OfflineDecks});</li>
 *   <li>the engine on the server ({@link OfflineReplay}: LocalMatch on the JVM, the bot moved as the browser moved it)
 *       reaches the post-game with the claimed game number, winner, end, turns and digest, within {@code budgetMs};</li>
 * </ol>
 * then it is credited by the server's own path for a game that ends ({@link MissionHooks#count}: missions, progression
 * and the activity log, exactly what the server counts for its own games against the bot) and kept in the match
 * history flagged {@code offline} ({@link MatchHistory#saveOffline}). Never ranked. At most {@code concurrentReplays}
 * games are played again at once on the server; a player who finds it busy is told to send it later.
 */
@Service
public class OfflineGames {

    private static final Logger log = LoggerFactory.getLogger(OfflineGames.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static final String ACCEPTED = "accepted";
    public static final String DUPLICATE = "duplicate";
    public static final String REJECTED = "rejected";
    /** Not decided now (the server is busy, or this player's replays of the day are spent): the site keeps it and sends it again later. */
    public static final String RETRY = "retry";

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final Pattern LEVEL = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final Pattern BUILD = Pattern.compile("[A-Za-z0-9._-]{0,64}");
    /** How far ahead of the server's clock a device's clock may be. */
    static final Duration CLOCK_SKEW = Duration.ofMinutes(10);

    /** The limits (application properties {@code offline-games.*}). */
    public record Rules(int dailyReplays, int windowDays, int maxActions, int maxBotMoves, int maxActionChars,
                        long budgetMs, int concurrentReplays, long busyWaitMs, long tickNanos) {
        public static Rules defaults() {
            return new Rules(200, 30, 5000, 20000, 8192, 120_000, 1, 20_000, LocalMatch.DEFAULT_TICK_NANOS);
        }
    }

    /** What the site is answered for one game. {@code credit}: what was counted, when accepted. */
    public record Outcome(String id, String status, String reason, String message, Credit credit) {
    }

    /**
     * What an accepted game counted: whether the player won, its turns, the missions' and progression's progress it made
     * for them (the same the server counts for its own games against the bot) and its entry in the match history.
     */
    public record Credit(boolean won, int turns, List<MissionEvents.Progress> progress, String matchId) {
    }

    private final OfflineDecks decks;
    private final OfflineGameStore store;
    private final MissionHooks hooks;
    private final MatchHistory history;
    private final Rules rules;
    private final ZoneId zone;
    private final Supplier<Instant> clock;
    private final Semaphore replays;

    @Autowired
    public OfflineGames(OfflineDecks decks, OfflineGameStore store, ObjectProvider<MissionHooks> hooks,
                        ObjectProvider<MatchHistory> history,
                        @Value("${offline-games.daily-replays:200}") int dailyReplays,
                        @Value("${offline-games.window-days:30}") int windowDays,
                        @Value("${offline-games.max-actions:5000}") int maxActions,
                        @Value("${offline-games.max-bot-moves:20000}") int maxBotMoves,
                        @Value("${offline-games.replay-budget-ms:120000}") long budgetMs,
                        @Value("${offline-games.concurrent-replays:1}") int concurrentReplays,
                        @Value("${wallet.zone:America/Santiago}") String zone) {
        this(decks, store, hooks.getIfAvailable(), history.getIfAvailable(), new Rules(dailyReplays, windowDays, maxActions, maxBotMoves,
                Rules.defaults().maxActionChars(), budgetMs, concurrentReplays, Rules.defaults().busyWaitMs(), Rules.defaults().tickNanos()), ZoneId.of(zone), Instant::now);
    }

    OfflineGames(OfflineDecks decks, OfflineGameStore store, MissionHooks hooks, MatchHistory history, Rules rules, ZoneId zone,
                 Supplier<Instant> clock) {
        this.decks = decks;
        this.store = store;
        this.hooks = hooks;
        this.history = history;
        this.rules = rules;
        this.zone = zone;
        this.clock = clock;
        this.replays = new Semaphore(Math.max(1, rules.concurrentReplays()), true);
    }

    public Rules rules() {
        return rules;
    }

    /** One game of the player ({@code userId}, the signed-in account): its verdict, credited when accepted. */
    public Outcome submit(String userId, OfflineGame game) {
        String id = game == null ? null : game.id();
        String bad = shape(game);
        if (bad != null) return rejected(id, "invalid", bad);

        OfflineGameStore.Verdict known = store.find(userId, id);
        if (known != null) {
            return ACCEPTED.equals(known.status())
                    ? new Outcome(id, DUPLICATE, "duplicate", "Esa partida ya estaba registrada", null)
                    : rejected(id, known.reason(), known.message());
        }
        Instant now = clock.get();
        Instant started = Instant.ofEpochMilli(game.startedAt());
        Instant ended = Instant.ofEpochMilli(game.endedAt());
        if (started.isBefore(now.minus(Duration.ofDays(rules.windowDays()))) || ended.isAfter(now.plus(CLOCK_SKEW))
                || ended.isBefore(started))
            return rejected(id, "window", "Solo se registran las partidas de los últimos " + rules.windowDays() + " días");
        OfflineGame.Claim claim = game.result();
        if (store.acceptedGame(userId, game.seed(), claim.gameNumber()))
            return new Outcome(id, DUPLICATE, "duplicate", "Esa partida ya estaba registrada", null);

        Instant today = LocalDate.ofInstant(now, zone).atStartOfDay(zone).toInstant();
        if (store.replayed(userId, today) >= rules.dailyReplays())
            return new Outcome(id, RETRY, "daily-replays", "Hoy ya revisamos muchas partidas tuyas: se enviará mañana", null);

        // The decks, as the server has them
        ObjectNode specs = JSON.createObjectNode();
        for (Map.Entry<String, Boolean> chosen : chosenDecks(game).entrySet()) {
            String deckId = chosen.getKey();
            if (specs.has(deckId)) continue;
            OfflineDecks.Made made = decks.make(userId, deckId, game.decks() == null ? null : game.decks().get(deckId), chosen.getValue());
            if (made.refusal() != null) return keep(userId, game, now, "deck", made.refusal());
            specs.set(deckId, made.spec());
        }

        // The game again, on the server's engine
        ObjectNode config = JSON.createObjectNode();
        config.put("seed", game.seed());
        config.put("userId", userId);
        if (game.botLevel() != null) config.put("botLevel", game.botLevel());
        config.put("time", game.startedAt());
        // The bot thinks as it thought in the browser: its budgets counted in readings of LocalMatch's default clock (the
        // site never changes it). Never the record's: a longer tick would make the bot think less, and lose
        config.put("botClock", "ticks");
        config.put("tickNanos", String.valueOf(rules.tickNanos()));
        config.set("decks", specs);
        OfflineReplay.Outcome replay;
        try {
            if (!replays.tryAcquire(rules.busyWaitMs(), TimeUnit.MILLISECONDS))
                return new Outcome(id, RETRY, "busy", "El servidor está ocupado: se enviará más tarde", null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(id, RETRY, "busy", "El servidor está ocupado: se enviará más tarde", null);
        }
        try {
            replay = OfflineReplay.play(config.toString(), game.actions(), System.nanoTime() + rules.budgetMs() * 1_000_000L);
        } finally {
            replays.release();
        }
        String mismatch = mismatch(replay, claim);
        if (mismatch != null) {
            log.info("Offline game {} of {} rejected: {}", id, userId, mismatch);
            String reason = replay.failure() == OfflineReplay.Failure.BUDGET ? "budget" : "mismatch";
            return keep(userId, game, now, reason, reason.equals("budget")
                    ? "La partida es demasiado larga para revisarla" : "La partida no coincide al jugarla de nuevo en el servidor");
        }

        // Accepted: kept first (a game sent twice at once is counted once), then credited as the server credits its own
        GameRoom room = replay.room();
        OfflineGameStore.Verdict verdict = new OfflineGameStore.Verdict(userId, id, ACCEPTED, null, null, game.seed(),
                claim.gameNumber(), room.getWinnerId(), claim.turns(), claim.digest(), game.engineBuild(), started, ended, now, null);
        if (!store.insert(verdict)) return new Outcome(id, DUPLICATE, "duplicate", "Esa partida ya estaba registrada", null);
        List<MissionEvents.Progress> progress = new ArrayList<>();
        for (MissionEvents.Progress p : MissionEvents.of(room, GameRoom.RoomStatus.IN_PROGRESS, replay.gameStartSeq())) {
            if (userId.equals(p.userId()) && !ProgressionRules.BOT_GAME_PLAYED.equals(p.counts())) progress.add(p);
        }
        if (hooks != null) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("offline", true);
            extra.put("offlineId", id);
            hooks.count(room, new MissionHooks.Before(GameRoom.RoomStatus.IN_PROGRESS, replay.gameStartSeq(),
                    new ArrayList<>(room.getPlayers().keySet())), extra);
        }
        String matchId = keepInHistory(room, game, id, started, ended, claim.turns());
        if (matchId != null) store.matchKept(userId, id, matchId);
        return new Outcome(id, ACCEPTED, null, null, new Credit(userId.equals(room.getWinnerId()), claim.turns(), progress, matchId));
    }

    /** What the replay gave against what the site claims; null when they agree. */
    static String mismatch(OfflineReplay.Outcome replay, OfflineGame.Claim claim) {
        if (replay.error() != null) return replay.error();
        if (replay.failure() == OfflineReplay.Failure.BUDGET) return "over the replay's budget";
        if (replay.failure() == OfflineReplay.Failure.NO_BOT_MOVE) return "the bot had no move where the game had one";
        JsonNode result = JSON.readTree(replay.match().result());
        List<String> wrong = new ArrayList<>();
        if (!"POST_GAME".equals(result.path("status").asString())) wrong.add("status " + result.path("status").asString());
        if (result.path("gamesEnded").asInt() != claim.gameNumber()) wrong.add("game " + result.path("gamesEnded").asInt());
        if (!Objects.equals(text(result, "winnerId"), claim.winnerId())) wrong.add("winner " + text(result, "winnerId"));
        if (!Objects.equals(text(result, "endReason"), claim.endReason())) wrong.add("end " + text(result, "endReason"));
        if (result.path("turns").asInt() != claim.turns()) wrong.add("turns " + result.path("turns").asInt());
        if (!Objects.equals(text(result, "digest"), claim.digest())) wrong.add("digest " + text(result, "digest"));
        return wrong.isEmpty() ? null : "the replay gives " + String.join(", ", wrong);
    }

    private static String text(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return v == null || v.isNull() ? null : v.asString();
    }

    /** The decks the actions chose, by id, and whether for the bot's seat (a deck chosen for both counts for the player). */
    static Map<String, Boolean> chosenDecks(OfflineGame game) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (OfflineGame.Step step : game.actions()) {
            JsonNode body;
            try {
                body = JSON.readTree(step.body());
            } catch (RuntimeException unreadable) {
                continue; // The engine drops it too
            }
            if (body == null || !"SET_DECK".equals(body.path("type").asString(null))) continue;
            JsonNode deckId = body.path("payload").path("deckId");
            if (!deckId.isString()) continue;
            boolean forBot = "bot".equals(body.path("payload").path("actAs").asString(null));
            out.merge(deckId.asString(), forBot, (a, b) -> a && b);
        }
        return out;
    }

    /** Why the game cannot even be looked at (its shape), or null. */
    String shape(OfflineGame g) {
        if (g == null || g.id() == null || !ID.matcher(g.id()).matches()) return "La partida no tiene un id válido";
        if (g.seed() == null || g.startedAt() == null || g.endedAt() == null || g.actions() == null || g.result() == null)
            return "A la partida le faltan datos";
        try {
            Long.parseLong(g.seed());
        } catch (NumberFormatException e) {
            return "La semilla de la partida no es válida";
        }
        if (g.botLevel() != null && !LEVEL.matcher(g.botLevel()).matches()) return "El nivel del bot no es válido";
        if (g.engineBuild() != null && !BUILD.matcher(g.engineBuild()).matches()) return "La versión del motor no es válida";
        if (g.result().gameNumber() < 1 || g.result().turns() < 0) return "El resultado de la partida no es válido";
        if (g.actions().isEmpty() || g.actions().size() > rules.maxActions()) return "La partida tiene demasiadas acciones";
        long moves = 0;
        for (OfflineGame.Step s : g.actions()) {
            if (s == null || s.body() == null || s.body().length() > rules.maxActionChars() || s.botMoves() < 0)
                return "Una acción de la partida no es válida";
            moves += s.botMoves();
        }
        if (moves > rules.maxBotMoves()) return "La partida tiene demasiadas jugadas del bot";
        return null;
    }

    /** A verdict that took the server's work (the decks looked up, the game played again): kept, so it is not redone. */
    private Outcome keep(String userId, OfflineGame g, Instant now, String reason, String message) {
        store.insert(new OfflineGameStore.Verdict(userId, g.id(), REJECTED, reason, message, g.seed(), g.result().gameNumber(),
                null, 0, g.result().digest(), g.engineBuild(), Instant.ofEpochMilli(g.startedAt()), Instant.ofEpochMilli(g.endedAt()), now, null));
        return rejected(g.id(), reason, message);
    }

    private static Outcome rejected(String id, String reason, String message) {
        return new Outcome(id, REJECTED, reason, message, null);
    }

    /** The game in the match history, flagged offline (never ranked, no log to watch it again); its id, or null. */
    private String keepInHistory(GameRoom room, OfflineGame game, String id, Instant started, Instant ended, int turns) {
        if (history == null || room.getStartSetup() == null) return null;
        try {
            MatchRecorder.Match match = new MatchRecorder.Match("offline-" + id, room.getStartSetup(), List.of(), started, ended,
                    room.getFirstPlayerId(), room.getWinnerId(), room.getEndReason(), turns, MatchReplay.fingerprint(room), room.getBotLevel());
            return history.saveOffline(match, game.actions().size(), id, game.engineBuild());
        } catch (RuntimeException e) {
            log.warn("Could not keep the offline game {} in the history: {}", id, e.getMessage());
            return null;
        }
    }
}
