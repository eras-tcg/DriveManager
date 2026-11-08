package com.drive.drive_manager.simulator.replay;

import com.drive.drive_manager.simulator.bot.BotRooms;
import com.drive.drive_manager.simulator.rank.RankService;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import com.drive.drive_manager.simulator.json.SimulatorJson;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The players' game history: each online game and each game against the bot, once over, in {@code matches} (who
 * played, with what, how it ended), and how to play it again in {@code match_logs} (its start and its actions, gzipped
 * JSON: only of each player's last {@link #LOGS_PER_PLAYER} games and for {@link #LOG_DAYS} days; the game stays in the
 * history without it). Test rooms are not kept. Saved off the room's thread: a slow database never
 * holds a game.
 */
@Service
public class MatchHistory {

    private static final Logger log = LoggerFactory.getLogger(MatchHistory.class);
    static final String MATCHES = "matches";
    static final String LOGS = "match_logs";
    /** How long a game can be played again; the game stays in the history after. */
    static final long LOG_DAYS = 14;
    /** A game can be played again while it is among the last ones of one of its players. */
    static final int LOGS_PER_PLAYER = 10;
    private static final String LOG_TTL_INDEX = "createdAt_1";
    /** The shape of a log (changes when what it holds changes). */
    static final int LOG_FORMAT = 1;

    private static final JsonMapper JSON = SimulatorJson.configure(JsonMapper.builder()).build();

    private final MongoTemplate mongo;
    /** The ranked ladder: a ranked game moves its players' rank when kept (none in tests). */
    private final RankService ranks;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "match-history");
        t.setDaemon(true);
        return t;
    });
    /** The cards' rules as this server runs them (their JSON files summed up): a log of other rules may not replay. */
    private final String rulesVersion = rulesVersion();

    public MatchHistory(MongoTemplate mongo, RankService ranks) {
        this.mongo = mongo;
        this.ranks = ranks;
        try {
            mongo.indexOps(MATCHES).ensureIndex(new Index().on("playerIds", Sort.Direction.ASC).on("endedAt", Sort.Direction.DESC));
            logsExpire();
        } catch (RuntimeException e) {
            log.warn("Could not create the match history indexes: {}", e.getMessage());
        }
    }

    /** The logs expire after LOG_DAYS (an index with another time is made again). */
    private void logsExpire() {
        Index ttl = new Index().on("createdAt", Sort.Direction.ASC).named(LOG_TTL_INDEX).expire(Duration.ofDays(LOG_DAYS));
        try {
            mongo.indexOps(LOGS).ensureIndex(ttl);
        } catch (RuntimeException conflict) {
            mongo.indexOps(LOGS).dropIndex(LOG_TTL_INDEX);
            mongo.indexOps(LOGS).ensureIndex(ttl);
        }
    }

    /** The saves still waiting are written before the server stops (a deploy, the server going to sleep). */
    @jakarta.annotation.PreDestroy
    void flush() throws InterruptedException {
        writer.shutdown();
        writer.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Keeps an ended game (test rooms are not kept); in the background. */
    void save(MatchRecorder.Match match) {
        if (match.setup().editor()) return;
        writer.submit(() -> {
            try {
                write(match);
            } catch (RuntimeException | IOException e) {
                log.warn("Could not save the game of room {}: {}", match.roomId(), e.getMessage());
            }
        });
    }

    private void write(MatchRecorder.Match match) throws IOException {
        ObjectId id = new ObjectId();
        MatchSetup setup = match.setup();
        Document doc = document(match, id);
        if (ranks != null && setup.ranked()) {
            Document changes = new Document();
            ranks.apply(match, id.toHexString()).forEach((userId, c) -> {
                changes.put(userId, RankService.changeDocument(c, id.toHexString(), match.roomId()));
                ranks.named(userId, setup.username(userId));
            });
            doc.append("rankChanges", changes);
        }
        mongo.insert(doc, MATCHES);
        mongo.insert(new Document("_id", id).append("createdAt", new Date()).append("rulesVersion", rulesVersion)
                .append("data", new Binary(gzip(write(setup, match.actions())))), LOGS);
        for (MatchSetup.Seat s : setup.seats()) if (!BotRooms.BOT.equals(s.userId())) dropOldLogs(s.userId());
    }

    /**
     * Keeps a game played offline against the bot (in the browser, then played again and accepted by the server:
     * OfflineGames) as the history keeps the server's own games against the bot, flagged {@code offline} (with the
     * offline game's id and the engine's build that played it), never ranked, and without a log to watch it again
     * (the server has the player's actions, not the bot's). Written at once; returns its id.
     */
    public String saveOffline(MatchRecorder.Match match, int actions, String offlineId, String engineBuild) {
        ObjectId id = new ObjectId();
        Document doc = document(match, id)
                .append("ranked", false)
                .append("actions", actions)
                .append("hasLog", false)
                .append("offline", true)
                .append("offlineId", offlineId)
                .append("engineBuild", engineBuild);
        mongo.insert(doc, MATCHES);
        return id.toHexString();
    }

    /** What {@code matches} keeps of a game. */
    private Document document(MatchRecorder.Match match, ObjectId id) {
        MatchSetup setup = match.setup();
        List<Document> players = new ArrayList<>();
        for (MatchSetup.Seat s : setup.seats()) {
            players.add(new Document("userId", s.userId()).append("username", s.username()).append("deckId", s.deckId())
                    .append("deckName", s.deckName()).append("starterCardId", s.starterCardId())
                    .append("bot", BotRooms.BOT.equals(s.userId())));
        }
        Document doc = new Document("_id", id)
                .append("roomId", match.roomId())
                .append("mode", setup.bot() ? "solo" : "online")
                .append("ranked", setup.ranked())
                .append("playerIds", setup.seats().stream().map(MatchSetup.Seat::userId).toList())
                .append("players", players)
                .append("firstPlayerId", match.firstPlayerId())
                .append("winnerId", match.winnerId())
                .append("endReason", match.endReason())
                .append("turns", match.turns())
                .append("actions", match.actions().size())
                .append("startedAt", Date.from(match.startedAt()))
                .append("endedAt", Date.from(match.endedAt()))
                .append("durationMs", Duration.between(match.startedAt(), match.endedAt()).toMillis())
                .append("rulesVersion", rulesVersion)
                .append("fingerprint", match.fingerprint())
                .append("hasLog", true)
                .append("botLevel", match.botLevel());
        return doc;
    }

    /**
     * The player's games past their last LOGS_PER_PLAYER lose their log, unless the game is still among the last ones
     * of its other player.
     */
    void dropOldLogs(String userId) {
        // Older than the logs' time their log is gone already (the index removed it)
        Date alive = new Date(System.currentTimeMillis() - Duration.ofDays(LOG_DAYS).toMillis());
        Query older = new Query(Criteria.where("playerIds").is(userId).and("hasLog").is(true).and("endedAt").gt(alive))
                .with(Sort.by(Sort.Direction.DESC, "endedAt")).skip(LOGS_PER_PLAYER);
        older.fields().include("playerIds");
        for (Document game : mongo.find(older, Document.class, MATCHES)) {
            ObjectId id = game.getObjectId("_id");
            boolean keptByOther = game.getList("playerIds", String.class, List.of()).stream()
                    .filter(other -> !other.equals(userId) && !BotRooms.BOT.equals(other))
                    .anyMatch(other -> recentIds(other).contains(id));
            if (keptByOther) continue;
            mongo.remove(new Query(Criteria.where("_id").is(id)), LOGS);
            mongo.updateFirst(new Query(Criteria.where("_id").is(id)),
                    new org.springframework.data.mongodb.core.query.Update().set("hasLog", false), MATCHES);
        }
    }

    /** The ids of the player's last LOGS_PER_PLAYER games. */
    private List<ObjectId> recentIds(String userId) {
        Query recent = new Query(Criteria.where("playerIds").is(userId)).with(Sort.by(Sort.Direction.DESC, "endedAt")).limit(LOGS_PER_PLAYER);
        recent.fields().include("_id");
        return mongo.find(recent, Document.class, MATCHES).stream().map(d -> d.getObjectId("_id")).toList();
    }

    /** A page of the player's games, the newest first (each as kept in {@code matches}). */
    public List<Document> of(String userId, int page, int size) {
        Query q = new Query(Criteria.where("playerIds").is(userId)).with(Sort.by(Sort.Direction.DESC, "endedAt"))
                .skip((long) Math.max(0, page) * size).limit(size);
        return mongo.find(q, Document.class, MATCHES).stream().map(MatchHistory::exposed).toList();
    }

    /** A game as kept, or null. */
    public Document get(String matchId) {
        if (!ObjectId.isValid(matchId)) return null;
        Document d = mongo.findById(new ObjectId(matchId), Document.class, MATCHES);
        return d == null ? null : exposed(d);
    }

    /** How to play a game again: its start and its actions, or null when its log is gone (or never was). */
    public Replayable log(String matchId) throws IOException {
        if (!ObjectId.isValid(matchId)) return null;
        Document d = mongo.findById(new ObjectId(matchId), Document.class, LOGS);
        if (d == null) return null;
        byte[] raw = gunzip(d.get("data", Binary.class).getData());
        Body body = read(raw);
        return new Replayable(body.format(), body.setup(), body.actions(), rulesVersion.equals(d.getString("rulesVersion")));
    }

    /** What a log holds, as kept. */
    record Body(int format, MatchSetup setup, List<MatchRecorder.Played> actions) {}

    static Body read(byte[] json) {
        return JSON.readValue(json, Body.class);
    }

    static byte[] write(MatchSetup setup, List<MatchRecorder.Played> actions) {
        return JSON.writeValueAsBytes(new Body(LOG_FORMAT, setup, actions));
    }

    /** A game's log read back; {@code sameRules}: kept by a server with the cards' rules of this one. */
    public record Replayable(int format, MatchSetup setup, List<MatchRecorder.Played> actions, boolean sameRules) {}

    private static Document exposed(Document d) {
        Document out = new Document(d);
        out.put("id", d.getObjectId("_id").toHexString());
        out.remove("_id");
        // A log past its time is gone (the index removed it)
        if (Boolean.TRUE.equals(d.getBoolean("hasLog")) && d.getDate("endedAt") != null
                && d.getDate("endedAt").getTime() < System.currentTimeMillis() - Duration.ofDays(LOG_DAYS).toMillis()) {
            out.put("hasLog", false);
        }
        return out;
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(bytes);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] bytes) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return in.readAllBytes();
        }
    }

    /** The cards' JSON files summed up, in a stable order. */
    static String rulesVersion() {
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:cards/**/*.json");
            Arrays.sort(files, Comparator.comparing(f -> String.valueOf(f.getFilename())));
            MessageDigest sum = MessageDigest.getInstance("SHA-256");
            for (Resource f : files) {
                try (InputStream in = f.getInputStream()) {
                    sum.update(in.readAllBytes());
                }
            }
            return HexFormat.of().formatHex(sum.digest()).substring(0, 16);
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            return "unknown";
        }
    }
}
