package com.drive.drive_manager.simulator.offline;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;

/**
 * The verdicts on offline games in Mongo, {@code offline_games}: {@code _id} is "{userId}:{gameId}", and a unique index
 * on (userId, seed, gameNumber) over the accepted ones counts each game once whatever id it is sent under.
 */
@Component
public class MongoOfflineGameStore implements OfflineGameStore {

    private static final Logger log = LoggerFactory.getLogger(MongoOfflineGameStore.class);
    static final String COLLECTION = "offline_games";

    private final MongoTemplate mongo;

    public MongoOfflineGameStore(MongoTemplate mongo) {
        this.mongo = mongo;
        try {
            mongo.indexOps(COLLECTION).ensureIndex(new Index().on("userId", Sort.Direction.ASC).on("seed", Sort.Direction.ASC)
                    .on("gameNumber", Sort.Direction.ASC).unique().named("one_accepted_game")
                    .partial(PartialIndexFilter.of(Criteria.where("status").is(OfflineGames.ACCEPTED))));
            mongo.indexOps(COLLECTION).ensureIndex(new Index().on("userId", Sort.Direction.ASC).on("at", Sort.Direction.DESC));
        } catch (RuntimeException e) {
            log.warn("Could not create the offline games' indexes: {}", e.getMessage());
        }
    }

    private static String key(String userId, String gameId) {
        return userId + ":" + gameId;
    }

    @Override
    public Verdict find(String userId, String gameId) {
        Document d = mongo.findById(key(userId, gameId), Document.class, COLLECTION);
        if (d == null) return null;
        return new Verdict(d.getString("userId"), d.getString("gameId"), d.getString("status"), d.getString("reason"),
                d.getString("message"), d.getString("seed"), d.getInteger("gameNumber", 0), d.getString("winnerId"),
                d.getInteger("turns", 0), d.getString("digest"), d.getString("engineBuild"), instant(d, "startedAt"),
                instant(d, "endedAt"), instant(d, "at"), d.getString("matchId"));
    }

    @Override
    public boolean acceptedGame(String userId, String seed, int gameNumber) {
        return mongo.exists(Query.query(Criteria.where("userId").is(userId).and("seed").is(seed).and("gameNumber").is(gameNumber)
                .and("status").is(OfflineGames.ACCEPTED)), COLLECTION);
    }

    @Override
    public long replayed(String userId, Instant since) {
        return mongo.count(Query.query(Criteria.where("userId").is(userId).and("at").gte(Date.from(since))), COLLECTION);
    }

    @Override
    public boolean insert(Verdict v) {
        Document d = new Document("_id", key(v.userId(), v.gameId()))
                .append("userId", v.userId()).append("gameId", v.gameId()).append("status", v.status())
                .append("reason", v.reason()).append("message", v.message()).append("seed", v.seed())
                .append("gameNumber", v.gameNumber()).append("winnerId", v.winnerId()).append("turns", v.turns())
                .append("digest", v.digest()).append("engineBuild", v.engineBuild())
                .append("startedAt", date(v.startedAt())).append("endedAt", date(v.endedAt())).append("at", date(v.at()))
                .append("matchId", v.matchId());
        try {
            mongo.insert(d, COLLECTION);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public void matchKept(String userId, String gameId, String matchId) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(key(userId, gameId))), new Update().set("matchId", matchId), COLLECTION);
    }

    private static Date date(Instant i) {
        return i == null ? null : Date.from(i);
    }

    private static Instant instant(Document d, String key) {
        Date date = d.getDate(key);
        return date == null ? null : date.toInstant();
    }
}
