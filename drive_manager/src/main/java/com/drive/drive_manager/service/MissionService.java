package com.drive.drive_manager.service;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Each player's progress on their missions, in the "missions" collection: one document per user id with the day and
 * the week it counts (day, week), the progress of that day's and week's missions (daily.{id}, weekly.{id}), the ones
 * already claimed (dailyClaimed, weeklyClaimed) and the last day they came in (lastLogin). A new day or week starts
 * its missions from zero. Claiming a completed mission gives its gems (WalletService), once.
 */
@Service
public class MissionService {

    static final String COLLECTION = "missions";

    private final MongoTemplate mongo;
    private final WalletService wallets;
    private final Supplier<LocalDate> today;

    @Autowired
    public MissionService(MongoTemplate mongo, WalletService wallets, @Value("${wallet.zone:America/Santiago}") String zone) {
        this(mongo, wallets, () -> LocalDate.now(ZoneId.of(zone)));
    }

    MissionService(MongoTemplate mongo, WalletService wallets, Supplier<LocalDate> today) {
        this.mongo = mongo;
        this.wallets = wallets;
        this.today = today;
    }

    /** A mission as the web shows it. */
    public record MissionView(String id, int progress, int target, int gems, boolean claimed) {
    }

    /** A player's missions of today and of this week, and when each set changes (dates, in the server's zone). */
    public record Board(List<MissionView> daily, List<MissionView> weekly, String nextDay, String nextWeek) {
    }

    /** Why a claim was refused. */
    public static class MissionException extends RuntimeException {
        public MissionException(String message) {
            super(message);
        }
    }

    /** The player's missions, counting today's login (once a day). */
    public Board open(String userId) {
        LocalDate day = today.get();
        String dayKey = MissionRules.dayKey(day);
        Document doc = current(userId, day);
        if (!dayKey.equals(doc.getString("lastLogin"))) {
            mongo.updateFirst(byId(userId).addCriteria(Criteria.where("lastLogin").ne(dayKey)), new Update().set("lastLogin", dayKey), COLLECTION);
            record(userId, MissionRules.LOGIN, 1);
            doc = current(userId, day);
        }
        return board(userId, day, doc);
    }

    /** Adds `amount` to whatever the player's missions of today and of this week count `counts`. */
    public void record(String userId, String counts, int amount) {
        if (amount <= 0) return;
        LocalDate day = today.get();
        current(userId, day);
        String dayKey = MissionRules.dayKey(day);
        String weekKey = MissionRules.weekKey(day);
        Update daily = new Update();
        for (MissionRules.Mission m : MissionRules.drawn(userId, dayKey, MissionRules.DAILY_COUNT)) {
            if (m.counts().equals(counts)) daily.inc("daily." + m.id(), amount);
        }
        if (!daily.getUpdateObject().isEmpty()) mongo.updateFirst(byId(userId).addCriteria(Criteria.where("day").is(dayKey)), daily, COLLECTION);
        Update weekly = new Update();
        for (MissionRules.Mission m : MissionRules.drawn(userId, weekKey, MissionRules.WEEKLY_COUNT)) {
            if (m.counts().equals(counts)) weekly.inc("weekly." + m.id(), amount);
        }
        if (!weekly.getUpdateObject().isEmpty()) mongo.updateFirst(byId(userId).addCriteria(Criteria.where("week").is(weekKey)), weekly, COLLECTION);
    }

    /** Claims a completed mission of today (weekly false) or of this week: its gems, once. */
    public Board claim(String userId, boolean weekly, String missionId) {
        LocalDate day = today.get();
        String key = weekly ? MissionRules.weekKey(day) : MissionRules.dayKey(day);
        MissionRules.Mission mission = MissionRules.drawn(userId, key, weekly ? MissionRules.WEEKLY_COUNT : MissionRules.DAILY_COUNT).stream()
                .filter(m -> m.id().equals(missionId)).findFirst().orElse(null);
        if (mission == null) throw new MissionException("Esa misión no es tuya en este momento");
        current(userId, day);
        String period = weekly ? "weekly" : "daily";
        Document after = mongo.findAndModify(
                byId(userId).addCriteria(Criteria.where(weekly ? "week" : "day").is(key)
                        .and(period + "." + mission.id()).gte(mission.target(weekly))
                        .and(period + "Claimed").ne(mission.id())),
                new Update().addToSet(period + "Claimed", mission.id()),
                FindAndModifyOptions.options().returnNew(true), Document.class, COLLECTION);
        if (after == null) throw new MissionException("Esa misión todavía no está cumplida, o ya la reclamaste");
        wallets.grant(userId, mission.gems(weekly));
        return board(userId, day, after);
    }

    /** The player's document, its day and week moved on to today (their progress starting from zero). */
    private Document current(String userId, LocalDate day) {
        String dayKey = MissionRules.dayKey(day);
        String weekKey = MissionRules.weekKey(day);
        // Created on first use (only then: setOnInsert), then moved on to today and this week
        mongo.upsert(byId(userId), new Update().setOnInsert("day", dayKey).setOnInsert("daily", new Document())
                .setOnInsert("dailyClaimed", new ArrayList<>()).setOnInsert("week", weekKey).setOnInsert("weekly", new Document())
                .setOnInsert("weeklyClaimed", new ArrayList<>()), COLLECTION);
        mongo.updateFirst(byId(userId).addCriteria(Criteria.where("day").ne(dayKey)),
                new Update().set("day", dayKey).set("daily", new Document()).set("dailyClaimed", new ArrayList<>()), COLLECTION);
        mongo.updateFirst(byId(userId).addCriteria(Criteria.where("week").ne(weekKey)),
                new Update().set("week", weekKey).set("weekly", new Document()).set("weeklyClaimed", new ArrayList<>()), COLLECTION);
        Document doc = mongo.findById(userId, Document.class, COLLECTION);
        return doc == null ? new Document() : doc;
    }

    private Board board(String userId, LocalDate day, Document doc) {
        return new Board(
                views(MissionRules.drawn(userId, MissionRules.dayKey(day), MissionRules.DAILY_COUNT), false, doc),
                views(MissionRules.drawn(userId, MissionRules.weekKey(day), MissionRules.WEEKLY_COUNT), true, doc),
                day.plusDays(1).toString(),
                MissionRules.nextWeek(day).toString());
    }

    private static List<MissionView> views(List<MissionRules.Mission> missions, boolean weekly, Document doc) {
        Document progress = doc.get(weekly ? "weekly" : "daily", Document.class);
        List<?> claimed = doc.getList(weekly ? "weeklyClaimed" : "dailyClaimed", Object.class, List.of());
        List<MissionView> out = new ArrayList<>();
        for (MissionRules.Mission m : missions) {
            int done = progress == null || !(progress.get(m.id()) instanceof Number n) ? 0 : n.intValue();
            out.add(new MissionView(m.id(), Math.min(done, m.target(weekly)), m.target(weekly), m.gems(weekly), claimed.contains(m.id())));
        }
        return out;
    }

    private static Query byId(String userId) {
        return Query.query(Criteria.where("_id").is(userId));
    }
}
