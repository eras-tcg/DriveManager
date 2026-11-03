package com.drive.drive_manager.service;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * What the players do on the site, for the administrators' activity log: one document per event in the
 * "activity_log" collection (userId, action, detail, at), kept RETENTION and then removed by Mongo. The account's
 * login is written by UsersService into the same collection. Recording never fails the request that caused it.
 */
@Service
public class ActivityLogService {

    static final String COLLECTION = "activity_log";
    /** How long an event is kept (a TTL index on "at"). */
    static final Duration RETENTION = Duration.ofDays(180);
    /** Most events a page of the log can ask for. */
    static final int MAX_PAGE = 200;

    public static final String LOGIN = "LOGIN";
    public static final String VISIT = "VISIT";
    public static final String SHOP_PURCHASE = "SHOP_PURCHASE";
    public static final String DECK_CREATE = "DECK_CREATE";
    public static final String DECK_UPDATE = "DECK_UPDATE";
    public static final String DECK_DELETE = "DECK_DELETE";
    public static final String ONLINE_GAME_START = "ONLINE_GAME_START";
    public static final String ONLINE_GAME_END = "ONLINE_GAME_END";
    public static final String SOLO_GAME_START = "SOLO_GAME_START";
    public static final String SOLO_GAME_END = "SOLO_GAME_END";
    /** Detail of a game: the other player's account id. */
    public static final String RIVAL_ID = "rivalId";

    private final MongoTemplate mongo;
    private final Supplier<LocalDate> today;
    /** The last day each player's visit was written, so a day's visit costs one write. */
    private final Map<String, LocalDate> visited = new ConcurrentHashMap<>();

    @Autowired
    public ActivityLogService(MongoTemplate mongo, @Value("${wallet.zone:America/Santiago}") String zone) {
        this(mongo, () -> LocalDate.now(ZoneId.of(zone)));
    }

    ActivityLogService(MongoTemplate mongo, Supplier<LocalDate> today) {
        this.mongo = mongo;
        this.today = today;
        try {
            mongo.indexOps(COLLECTION).ensureIndex(new Index().on("at", Sort.Direction.DESC).expire(RETENTION));
            mongo.indexOps(COLLECTION).ensureIndex(new Index().on("userId", Sort.Direction.ASC).on("at", Sort.Direction.DESC));
        } catch (RuntimeException e) {
            // Without the indexes the log still works, only slower and kept forever
        }
    }

    /** One event of a player, now. */
    public void record(String userId, String action, Map<String, Object> detail) {
        if (userId == null || userId.isBlank()) return;
        try {
            Document event = new Document("userId", userId).append("action", action).append("at", Instant.now());
            if (detail != null && !detail.isEmpty()) event.append("detail", new Document(detail));
            mongo.insert(event, COLLECTION);
        } catch (RuntimeException e) {
            // The log is for the administrators: a failure there must not break what the player did
        }
    }

    /** The player came in today: once a day (its id is the player and the day, so two servers cannot write it twice). */
    public void visit(String userId) {
        if (userId == null) return;
        LocalDate day = today.get();
        if (day.equals(visited.get(userId))) return;
        visited.put(userId, day);
        try {
            mongo.insert(new Document("_id", "visit-" + userId + "-" + day).append("userId", userId).append("action", VISIT)
                    .append("at", Instant.now()), COLLECTION);
        } catch (DuplicateKeyException e) {
            // Already written today (the server restarted)
        } catch (RuntimeException e) {
            visited.remove(userId);
        }
    }

    /** An event as the administrators see it, with the player's current name. */
    public record Entry(String id, String userId, String username, String action, Map<String, Object> detail, Instant at) {
    }

    /** A page of the log, newest first, and how many events match. */
    public record Page(List<Entry> items, long total) {
    }

    /**
     * The events matching every filter given: actions (any of them), a player's name (part of it, any case), from and
     * to (inclusive instants).
     */
    public Page search(List<String> actions, String user, Instant from, Instant to, int page, int size) {
        List<Criteria> filters = new ArrayList<>();
        if (actions != null && !actions.isEmpty()) filters.add(Criteria.where("action").in(actions));
        if (user != null && !user.isBlank()) {
            Set<String> ids = userIdsNamed(user.trim());
            if (ids.isEmpty()) return new Page(List.of(), 0);
            filters.add(Criteria.where("userId").in(ids));
        }
        if (from != null || to != null) {
            Criteria at = Criteria.where("at");
            if (from != null) at = at.gte(from);
            if (to != null) at = at.lte(to);
            filters.add(at);
        }
        Query query = filters.isEmpty() ? new Query() : new Query(new Criteria().andOperator(filters));
        long total = mongo.count(query, COLLECTION);
        int limit = Math.max(1, Math.min(size, MAX_PAGE));
        query.with(Sort.by(Sort.Direction.DESC, "at")).skip((long) Math.max(0, page) * limit).limit(limit);
        List<Document> docs = mongo.find(query, Document.class, COLLECTION);
        List<String> ids = new ArrayList<>();
        for (Document d : docs) {
            ids.add(d.getString("userId"));
            if (detailOf(d).get(RIVAL_ID) instanceof String rival) ids.add(rival);
        }
        Map<String, String> names = usernames(ids.stream().distinct().toList());
        List<Entry> items = docs.stream().map(d -> new Entry(String.valueOf(d.get("_id")), d.getString("userId"),
                names.get(d.getString("userId")), d.getString("action"), withRivalName(detailOf(d), names), toInstant(d.get("at")))).toList();
        return new Page(items, total);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailOf(Document d) {
        Object detail = d.get("detail");
        return detail instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
    }

    /** A game's rival, kept by id, shown by their current name ("rival"). */
    private static Map<String, Object> withRivalName(Map<String, Object> detail, Map<String, String> names) {
        if (detail.get(RIVAL_ID) instanceof String rival && names.containsKey(rival)) detail.put("rival", names.get(rival));
        return detail;
    }

    private static Instant toInstant(Object at) {
        if (at instanceof java.util.Date date) return date.toInstant();
        return at instanceof Instant instant ? instant : null;
    }

    /** Ids of the accounts whose username contains `text`. */
    private Set<String> userIdsNamed(String text) {
        Query query = new Query(Criteria.where("username").regex(Pattern.quote(text), "i"));
        query.fields().include("_id");
        Set<String> ids = new java.util.HashSet<>();
        for (Document d : mongo.find(query, Document.class, "users")) ids.add(String.valueOf(d.get("_id")));
        return ids;
    }

    /** Current username of each account id (missing for a deleted account). */
    private Map<String, String> usernames(List<String> ids) {
        List<Object> keys = new ArrayList<>();
        for (String id : ids) {
            if (id == null) continue;
            keys.add(ObjectId.isValid(id) ? new ObjectId(id) : id);
        }
        Map<String, String> names = new LinkedHashMap<>();
        if (keys.isEmpty()) return names;
        Query query = new Query(Criteria.where("_id").in(keys));
        query.fields().include("username");
        for (Document d : mongo.find(query, Document.class, "users")) names.put(String.valueOf(d.get("_id")), d.getString("username"));
        return names;
    }
}
