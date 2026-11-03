package com.drive.drive_manager.config;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a signed token still stands for its account: the account exists and the token carries its current
 * token_version. UsersService raises that version when the password, the role or the session list changes, so a
 * stolen or outdated token stops working here too (its permissions with it). The users collection is shared with
 * UsersService; each account is read at most every {@link #TTL_MS} ms.
 */
@Component
public class SessionCheck {

    static final long TTL_MS = 30_000;
    private static final int MAX_CACHED = 10_000;

    private final MongoTemplate mongo;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Integer version, long readAt) {}

    @Autowired
    public SessionCheck(MongoTemplate mongo) {
        this(mongo, Clock.systemUTC());
    }

    SessionCheck(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    /** {@code tokenVersion}: the claim in the token (null when it has none). */
    public boolean isCurrent(String userId, Integer tokenVersion) {
        if (userId == null) return false;
        long now = clock.millis();
        Cached cached = cache.get(userId);
        if (cached == null || now - cached.readAt() > TTL_MS) {
            if (cache.size() > MAX_CACHED) cache.clear();
            Document user = mongo.findById(userId, Document.class, "users");
            cached = new Cached(user == null ? null : user.getInteger("tokenVersion", 0), now);
            cache.put(userId, cached);
        }
        if (cached.version() == null) return false;
        return cached.version().equals(tokenVersion == null ? 0 : tokenVersion);
    }
}
