package com.drive.drive_manager.simulator.rank;

import com.drive.drive_manager.simulator.bot.BotCatalog;
import com.drive.drive_manager.simulator.bot.BotEdge;
import com.drive.drive_manager.simulator.bot.BotRooms;
import com.drive.drive_manager.simulator.rank.RankRules.Change;
import com.drive.drive_manager.simulator.rank.RankRules.Standing;
import com.drive.drive_manager.simulator.rank.RankRules.Tier;
import com.drive.drive_manager.simulator.replay.MatchRecorder;
import com.drive.drive_manager.simulator.replay.MatchSetup;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Each player's place in the ranked ladder ({@code ranks}, one document per user): their standing (RankRules) and
 * what their last ranked game changed. Moved once per ranked game with a winner, by the match history's writer (one
 * thread: two games of a player never overlap).
 */
@Service
public class RankService {

    static final String RANKS = "ranks";
    /** Most players on the Retador board. */
    static final int BOARD = 50;

    private final MongoTemplate mongo;

    public RankService(MongoTemplate mongo) {
        this.mongo = mongo;
        try {
            // The Retador board: by points, then strength
            if (mongo != null) mongo.indexOps(RANKS).ensureIndex(new org.springframework.data.mongodb.core.index.Index()
                    .on("tier", Sort.Direction.ASC).on("points", Sort.Direction.DESC).on("mmr", Sort.Direction.DESC));
        } catch (RuntimeException e) {
            // Not fatal: the board reads without it
        }
    }

    /** The player's standing (a new player's when they never played ranked). */
    public Standing of(String userId) {
        Document d = mongo.findById(userId, Document.class, RANKS);
        return d == null ? Standing.start() : standing(d);
    }

    /** The player's rank as the web shows it: the standing and their last change, or null when none. */
    public Document view(String userId) {
        Document d = mongo.findById(userId, Document.class, RANKS);
        Document out = toDocument(d == null ? Standing.start() : standing(d));
        out.put("lastChange", d == null ? null : d.get("lastChange"));
        out.remove("mmr");
        return out;
    }

    /**
     * Moves the rank of each player of a ranked game that has a winner (a bot plays at its level's strength and has no
     * rank). Returns each player's change, by user id.
     */
    /** How strong the bot of a game was: its level's strength, the hardest one's raised by its edge. */
    static int botStrength(MatchRecorder.Match match) {
        if (BotCatalog.HARD.equals(match.botLevel())) return BotEdge.strength(match.setup().botEdge());
        return RankRules.BOT_MMR.getOrDefault(match.botLevel(), RankRules.START_MMR);
    }

    public Map<String, Change> apply(MatchRecorder.Match match, String matchId) {
        Map<String, Change> changes = new LinkedHashMap<>();
        if (!match.setup().ranked() || match.winnerId() == null) return changes;
        Map<String, Standing> before = new LinkedHashMap<>();
        for (MatchSetup.Seat s : match.setup().seats()) if (!BotRooms.BOT.equals(s.userId())) before.put(s.userId(), of(s.userId()));
        for (Map.Entry<String, Standing> e : before.entrySet()) {
            String rival = match.setup().seats().stream().map(MatchSetup.Seat::userId).filter(id -> !id.equals(e.getKey())).findFirst().orElse(null);
            int rivalMmr = rival == null ? RankRules.START_MMR
                    : BotRooms.BOT.equals(rival) ? botStrength(match)
                    : before.getOrDefault(rival, Standing.start()).mmr();
            Change change = RankRules.after(e.getValue(), e.getKey().equals(match.winnerId()), rivalMmr);
            changes.put(e.getKey(), change);
            org.springframework.data.mongodb.core.query.Update set = new org.springframework.data.mongodb.core.query.Update();
            toDocument(change.after()).forEach(set::set);
            set.set("updatedAt", new Date()).set("lastChange", changeDocument(change, matchId, match.roomId()));
            // By the same query as findById (a user id that looks like an ObjectId is kept as one)
            mongo.upsert(new Query(Criteria.where("_id").is(e.getKey())), set, RANKS);
        }
        return changes;
    }

    /** The Retador board: the most points first (then the strongest). */
    public List<Document> challengers() {
        Query q = new Query(Criteria.where("tier").is(Tier.CHALLENGER.name()))
                .with(Sort.by(Sort.Direction.DESC, "points").and(Sort.by(Sort.Direction.DESC, "mmr"))).limit(BOARD);
        List<Document> out = new ArrayList<>();
        for (Document d : mongo.find(q, Document.class, RANKS)) {
            out.add(new Document("userId", String.valueOf(d.get("_id"))).append("username", d.getString("username")).append("points", d.get("points")));
        }
        return out;
    }

    /** Keeps the player's name beside their rank (for the Retador board). */
    public void named(String userId, String username) {
        mongo.updateFirst(new Query(Criteria.where("_id").is(userId)),
                new org.springframework.data.mongodb.core.query.Update().set("username", username), RANKS);
    }

    public static Document changeDocument(Change c, String matchId, String roomId) {
        return new Document("matchId", matchId).append("roomId", roomId).append("at", new Date())
                .append("before", publicDocument(c.before())).append("after", publicDocument(c.after()))
                .append("summary", c.summary()).append("promoted", c.after().tier().ordinal() > c.before().tier().ordinal())
                .append("demoted", c.after().tier().ordinal() < c.before().tier().ordinal());
    }

    /** The standing as players see it: without the hidden strength (MMR). */
    static Document publicDocument(Standing s) {
        Document d = toDocument(s);
        d.remove("mmr");
        return d;
    }

    static Document toDocument(Standing s) {
        return new Document("tier", s.tier().name()).append("tierLabel", s.tier().label).append("gems", s.gems())
                .append("maxGems", s.tier().gems).append("shields", s.shields()).append("inSeries", s.inSeries())
                .append("seriesWins", s.seriesWins()).append("seriesLosses", s.seriesLosses()).append("points", s.points())
                .append("mmr", s.mmr()).append("games", s.games()).append("wins", s.wins()).append("losses", s.losses())
                .append("peak", s.peak().name());
    }

    static Standing standing(Document d) {
        return new Standing(num(d, "mmr", RankRules.START_MMR), num(d, "games", 0), num(d, "wins", 0), num(d, "losses", 0),
                Tier.valueOf(d.get("tier", Tier.IRON.name())), num(d, "gems", 0), num(d, "shields", 0),
                Boolean.TRUE.equals(d.getBoolean("inSeries")), num(d, "seriesWins", 0), num(d, "seriesLosses", 0),
                num(d, "points", 0), Tier.valueOf(d.get("peak", Tier.IRON.name())));
    }

    private static int num(Document d, String key, int otherwise) {
        Object v = d.get(key);
        return v instanceof Number n ? n.intValue() : otherwise;
    }
}
