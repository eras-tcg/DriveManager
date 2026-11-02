package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.EditionMetadata;
import com.drive.drive_manager.repository.EditionMetadataRepository;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
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

    /** The wallet of `userId` as of today (created on first use, with the day's reward given). */
    public WalletRules.Opened open(String userId) {
        Document saved = mongo.findById(userId, Document.class, COLLECTION);
        WalletRules.State state = saved == null ? null : new WalletRules.State(saved.getInteger("gems", 0), saved.getString("lastReward"));
        WalletRules.Opened opened = WalletRules.open(state, today());
        if (saved == null || opened.reward() > 0) {
            mongo.upsert(byId(userId), new Update().set("gems", opened.state().gems()).set("lastReward", opened.state().lastReward()), COLLECTION);
        }
        return opened;
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

    /** The structure decks an account already bought (each can be bought only once). */
    public List<String> structures(String userId) {
        Document saved = mongo.findById(userId, Document.class, COLLECTION);
        return saved == null ? List.of() : saved.getList("structures", String.class, List.of());
    }

    /** Adds `gems` to an account (a negative amount takes them away, never below zero). For testing the shop. */
    public int grant(String userId, int gems) {
        int current = open(userId).state().gems();
        int next = Math.max(0, current + gems);
        mongo.upsert(byId(userId), new Update().set("gems", next), COLLECTION);
        return next;
    }

    private String today() {
        return LocalDate.now(zone).toString();
    }

    private static Query byId(String userId) {
        return Query.query(Criteria.where("_id").is(userId));
    }
}
