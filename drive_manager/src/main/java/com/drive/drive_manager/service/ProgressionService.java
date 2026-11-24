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
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Each player's progression missions (ProgressionRules), in the "progression" collection: one document per user id
 * with lifetime counters (counts.{what}), the pages visited (visited), the welcome steps claimed (welcome), the tiers
 * claimed of each milestone (milestones.{id}), the badges won (badges) and the last day they came in (lastLogin).
 * What an account already has (one of ST1–ST4, cards) is read where it lives, so players who were there before
 * these missions find those done; a deck saved and an icon set count from now on (DeckListController,
 * AvatarController). Claiming is one atomic step, so nothing is given twice.
 */
@Service
public class ProgressionService {

    static final String COLLECTION = "progression";

    private final MongoTemplate mongo;
    private final WalletService wallets;
    private final CollectionService collections;
    private final Supplier<LocalDate> today;

    @Autowired
    public ProgressionService(MongoTemplate mongo, WalletService wallets, CollectionService collections,
                              @Value("${wallet.zone:America/Santiago}") String zone) {
        this(mongo, wallets, collections, () -> LocalDate.now(ZoneId.of(zone)));
    }

    ProgressionService(MongoTemplate mongo, WalletService wallets, CollectionService collections, Supplier<LocalDate> today) {
        this.mongo = mongo;
        this.wallets = wallets;
        this.collections = collections;
        this.today = today;
    }

    /** A player's welcome chain, milestones and badges. */
    public record Board(List<ProgressionRules.StepView> welcome, List<ProgressionRules.MilestoneView> milestones, List<String> badges) {
    }

    /** Why a claim was refused. */
    public static class ProgressionException extends RuntimeException {
        public ProgressionException(String message) {
            super(message);
        }
    }

    /** The player's progression, counting today's login (once a day). */
    public Board open(String userId) {
        String day = today.get().toString();
        ensure(userId);
        mongo.updateFirst(byId(userId).addCriteria(Criteria.where("lastLogin").ne(day)),
                new Update().set("lastLogin", day).inc("counts." + MissionRules.LOGIN, 1), COLLECTION);
        return board(userId, load(userId));
    }

    /** Adds `amount` to a lifetime counter. */
    public void record(String userId, String counts, int amount) {
        if (amount <= 0 || userId == null) return;
        mongo.upsert(byId(userId), new Update().inc("counts." + counts, amount), COLLECTION);
    }

    /** Notes a visit to one of the pages that count (others are ignored). */
    public void visit(String userId, String page) {
        if (!ProgressionRules.PAGES.contains(page)) return;
        mongo.upsert(byId(userId), new Update().addToSet("visited", page), COLLECTION);
    }

    /** Claims a welcome step: its gems (the last one also its badge). */
    public Board claimWelcome(String userId, String stepId) {
        ensure(userId);
        Document doc = load(userId);
        ProgressionRules.Facts facts = facts(userId, doc);
        Set<String> claimed = claimed(doc);
        ProgressionRules.Step step = ProgressionRules.step(stepId);
        String refusal = ProgressionRules.refusal(step, facts, claimed);
        if (refusal != null) throw new ProgressionException(refusal);

        List<String> before = ProgressionRules.WELCOME.stream().map(ProgressionRules.Step::id).takeWhile(id -> !id.equals(step.id())).toList();
        Criteria open = Criteria.where("_id").is(userId).and("welcome").ne(step.id());
        if (!before.isEmpty()) open = new Criteria().andOperator(open, Criteria.where("welcome").all(before));
        Update mark = new Update().addToSet("welcome", step.id());
        if (step.id().equals(ProgressionRules.GRADUATE_STEP)) mark.addToSet("badges", ProgressionRules.GRADUATE_BADGE);
        Document after = mongo.findAndModify(Query.query(open), mark, FindAndModifyOptions.options().returnNew(true), Document.class, COLLECTION);
        if (after == null) throw new ProgressionException("Ya reclamaste esa misión");

        wallets.grant(userId, step.gems());
        return board(userId, after);
    }

    /** Claims a milestone's next tier: its gems. */
    public Board claimMilestone(String userId, String milestoneId) {
        ensure(userId);
        Document doc = load(userId);
        ProgressionRules.Milestone milestone = ProgressionRules.milestone(milestoneId);
        int tier = tiers(doc).getOrDefault(milestoneId, 0);
        String refusal = ProgressionRules.refusal(milestone, facts(userId, doc), tier);
        if (refusal != null) throw new ProgressionException(refusal);
        String field = "milestones." + milestone.id();
        Criteria same = tier == 0 ? Criteria.where(field).in(Arrays.asList(null, 0)) : Criteria.where(field).is(tier);
        Document after = mongo.findAndModify(Query.query(Criteria.where("_id").is(userId).andOperator(same)), new Update().set(field, tier + 1),
                FindAndModifyOptions.options().returnNew(true), Document.class, COLLECTION);
        if (after == null) throw new ProgressionException("Ya reclamaste ese nivel");
        wallets.grant(userId, milestone.gems()[tier]);
        return board(userId, after);
    }

    private Board board(String userId, Document doc) {
        ProgressionRules.Facts facts = facts(userId, doc);
        return new Board(ProgressionRules.welcome(facts, claimed(doc)), ProgressionRules.milestones(facts, tiers(doc)),
                doc.getList("badges", String.class, List.of()));
    }

    private ProgressionRules.Facts facts(String userId, Document doc) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Document saved = doc.get("counts", Document.class);
        if (saved != null) saved.forEach((k, v) -> {
            if (v instanceof Number n) counts.put(k, n.intValue());
        });
        return new ProgressionRules.Facts(counts, new HashSet<>(doc.getList("visited", String.class, List.of())),
                WalletRules.ownsStarter(wallets.structures(userId)), collections.of(userId).cards().size());
    }

    private static Set<String> claimed(Document doc) {
        return new HashSet<>(doc.getList("welcome", String.class, List.of()));
    }

    private static Map<String, Integer> tiers(Document doc) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Document saved = doc.get("milestones", Document.class);
        if (saved != null) saved.forEach((k, v) -> {
            if (v instanceof Number n) out.put(k, n.intValue());
        });
        return out;
    }

    /** Creates the player's document on first use. */
    private void ensure(String userId) {
        mongo.upsert(byId(userId), new Update().setOnInsert("counts", new Document()), COLLECTION);
    }

    private Document load(String userId) {
        Document doc = mongo.findById(userId, Document.class, COLLECTION);
        return doc == null ? new Document() : doc;
    }

    private static Query byId(String userId) {
        return Query.query(Criteria.where("_id").is(userId));
    }
}
