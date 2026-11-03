package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.EditionMetadata;
import com.drive.drive_manager.repository.EditionMetadataRepository;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Each account's simulated gems, kept in the "wallets" collection (one document per user id: gems, lastReward, and
 * the structure decks it bought, each only once: structures).
 * Reading it gives the daily reward; buying charges the server's price in one atomic step, so a purchase can never
 * leave the balance below zero.
 */
@Service
public class WalletService {

    static final String COLLECTION = "wallets";

    private final MongoTemplate mongo;
    private final EditionMetadataRepository editions;
    private final ZoneId zone;

    public WalletService(MongoTemplate mongo, EditionMetadataRepository editions, @Value("${wallet.zone:America/Santiago}") String zone) {
        this.mongo = mongo;
        this.editions = editions;
        this.zone = ZoneId.of(zone);
    }

    /** The gems after a purchase. */
    public record Purchase(int gems, int price) {
    }

    /** Why a purchase was refused. */
    public static class PurchaseException extends RuntimeException {
        public PurchaseException(String message) {
            super(message);
        }
    }

    /**
     * The wallet of `userId` as of today (created on first use, with the day's reward given), as WalletRules.open
     * says. Each step is one atomic update that adds rather than overwrites, so a purchase or a reward running at the
     * same time is never lost: the daily reward is given only by the update that moves lastReward to today, and a new
     * wallet is only ever inserted, never reset.
     */
    public WalletRules.Opened open(String userId) {
        String today = today();
        Document rewarded = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(userId).and("lastReward").ne(today)),
                new Update().inc("gems", WalletRules.DAILY_GEMS).set("lastReward", today),
                FindAndModifyOptions.options().returnNew(true), Document.class, COLLECTION);
        if (rewarded != null) return new WalletRules.Opened(state(rewarded), WalletRules.DAILY_GEMS);
        try {
            mongo.upsert(byId(userId), new Update().setOnInsert("gems", WalletRules.STARTING_GEMS).setOnInsert("lastReward", today), COLLECTION);
        } catch (DuplicateKeyException e) {
            // Another request created it at the same moment: that one is the wallet
        }
        Document saved = mongo.findById(userId, Document.class, COLLECTION);
        return new WalletRules.Opened(saved == null ? new WalletRules.State(WalletRules.STARTING_GEMS, today) : state(saved), 0);
    }

    /**
     * Buys a "pack", a "box" or a "structure" deck at the server's price. A structure deck is bought once per account:
     * charging and noting it is one atomic step, so it can never be bought twice.
     */
    public Purchase buy(String userId, String editionId, String product) {
        EditionMetadata edition = editionId == null ? null : editions.findById(editionId).orElse(null);
        Integer price = WalletRules.priceOf(edition, product);
        if (price == null) throw new PurchaseException("Ese producto no está a la venta");
        open(userId);
        boolean structure = "structure".equals(product);
        Criteria enough = Criteria.where("_id").is(userId).and("gems").gte(price);
        Update charge = new Update().inc("gems", -price);
        if (structure) {
            enough.and("structures").ne(editionId);
            charge.addToSet("structures", editionId);
        }
        Document after = mongo.findAndModify(Query.query(enough), charge, FindAndModifyOptions.options().returnNew(true), Document.class, COLLECTION);
        if (after == null) {
            throw new PurchaseException(structure && structures(userId).contains(editionId) ? "Ya compraste este structure deck" : "No te alcanzan las gemas");
        }
        return new Purchase(after.getInteger("gems", 0), price);
    }

    /**
     * Notes a structure deck as the account's without charging it (a reward): the gems after it, or a refusal when it
     * is not a structure deck or the account already has it. One atomic step, like a purchase.
     */
    public Purchase gift(String userId, String editionId) {
        EditionMetadata edition = editionId == null ? null : editions.findById(editionId).orElse(null);
        if (WalletRules.priceOf(edition, "structure") == null) throw new PurchaseException("Ese structure deck no existe");
        open(userId);
        Document after = mongo.findAndModify(Query.query(Criteria.where("_id").is(userId).and("structures").ne(editionId)),
                new Update().addToSet("structures", editionId), FindAndModifyOptions.options().returnNew(true), Document.class, COLLECTION);
        if (after == null) throw new PurchaseException("Ya tienes este structure deck");
        return new Purchase(after.getInteger("gems", 0), 0);
    }

    /** The structure decks an account already bought (each can be bought only once). */
    public List<String> structures(String userId) {
        Document saved = mongo.findById(userId, Document.class, COLLECTION);
        return saved == null ? List.of() : saved.getList("structures", String.class, List.of());
    }

    /**
     * Adds `gems` to an account (a negative amount takes them away, never below zero): the gems after it. One atomic
     * update (gems = max(0, gems + amount)), so a purchase or another reward at the same moment is never overwritten.
     */
    public int grant(String userId, int gems) {
        open(userId);
        AggregationUpdate add = AggregationUpdate.from(List.of(context -> new Document("$set", new Document("gems",
                new Document("$max", List.of(0, new Document("$add", List.of("$gems", gems))))))));
        Document after = mongo.findAndModify(byId(userId), add, FindAndModifyOptions.options().returnNew(true), Document.class, COLLECTION);
        return after == null ? 0 : gems(after);
    }

    private static WalletRules.State state(Document saved) {
        return new WalletRules.State(gems(saved), saved.getString("lastReward"));
    }

    /** The gems saved, whether the server stored them as an int or (after a large sum) as a long. */
    private static int gems(Document saved) {
        return saved.get("gems") instanceof Number n ? (int) Math.min(Integer.MAX_VALUE, n.longValue()) : 0;
    }

    private String today() {
        return LocalDate.now(zone).toString();
    }

    private static Query byId(String userId) {
        return Query.query(Criteria.where("_id").is(userId));
    }
}
