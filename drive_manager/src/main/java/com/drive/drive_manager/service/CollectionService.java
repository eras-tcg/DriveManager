package com.drive.drive_manager.service;

import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;
import java.util.regex.Pattern;

/**
 * Each player's cards and fragments, in the "collections" collection: one document per user id with
 * cards.{cardId}.{plain|foil|prism} (copies) and fragments.{rarity}. Every change is one atomic update guarded by what
 * it takes, so copies and fragments never go below zero. Rules in CollectionRules.
 */
@Service
public class CollectionService {

    static final String COLLECTION = "collections";
    /** Card ids are Drive file ids: nothing that could reach into another field of the document. */
    private static final Pattern CARD_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    private final MongoTemplate mongo;
    private final CardPoolService pool;
    private final DoubleSupplier random;

    @Autowired
    public CollectionService(MongoTemplate mongo, CardPoolService pool) {
        this(mongo, pool, new SecureRandom()::nextDouble);
    }

    CollectionService(MongoTemplate mongo, CardPoolService pool, DoubleSupplier random) {
        this.mongo = mongo;
        this.pool = pool;
        this.random = random;
    }

    /** One copy obtained: a card and its finish. */
    public record Pull(String cardId, String finish) {
    }

    /** A collection as the web reads it: copies by card and finish, fragments by rarity. */
    public record View(Map<String, Map<String, Integer>> cards, Map<String, Integer> fragments) {
    }

    /** What a craft gave: the finishes crafted, in order, and the collection after it. */
    public record Crafted(List<String> finishes, View collection) {
    }

    /** Why a change was refused. */
    public static class CollectionException extends RuntimeException {
        public CollectionException(String message) {
            super(message);
        }
    }

    public View of(String userId) {
        return view(mongo.findById(userId, Document.class, COLLECTION));
    }

    /** Adds what came out of an opening. */
    public void add(String userId, List<Pull> pulls) {
        if (pulls.isEmpty()) return;
        Update update = new Update();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Pull p : pulls) counts.merge(field(p.cardId(), p.finish()), 1, Integer::sum);
        counts.forEach(update::inc);
        mongo.upsert(byId(userId), update, COLLECTION);
    }

    /** Breaks `count` copies of a card in one finish, for fragments of its rarity. */
    public View breakCopies(String userId, String cardId, String finish, int count) {
        if (count < 1 || !CollectionRules.isFinish(finish)) throw new CollectionException("Nada que romper");
        String rarity = rarity(cardId);
        String copies = field(cardId, finish);
        Document after = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(userId).and(copies).gte(count)),
                new Update().inc(copies, -count).inc("fragments." + rarity, count * CollectionRules.fragmentsOf(finish)),
                FindAndModifyOptions.options().returnNew(true), Document.class, COLLECTION);
        if (after == null) throw new CollectionException("No tienes esas copias");
        return view(after);
    }

    /** Crafts `count` copies of a card, CRAFT_COST fragments of its rarity each; each one's finish comes at random. */
    public Crafted craft(String userId, String cardId, int count) {
        if (count < 1) throw new CollectionException("Nada que fabricar");
        List<String> finishes = new ArrayList<>();
        for (int i = 0; i < count; i++) finishes.add(CollectionRules.rollFinish(random));
        return spend(userId, cardId, finishes);
    }

    /**
     * Crafts a card until one comes out at least as fine as `target`, or the fragments of its rarity run out (or the
     * `maxFragments` the player is willing to spend, when given).
     */
    public Crafted craftUntil(String userId, String cardId, String target, Integer maxFragments) {
        if (!CollectionRules.isFinish(target)) throw new CollectionException("Ese acabado no existe");
        String rarity = rarity(cardId);
        int fragments = of(userId).fragments().getOrDefault(rarity, 0);
        if (maxFragments != null) fragments = Math.min(fragments, Math.max(0, maxFragments));
        List<String> finishes = CollectionRules.craftUntil(target, fragments, random);
        if (finishes.isEmpty()) throw new CollectionException("No te alcanzan los fragmentos");
        return spend(userId, cardId, finishes);
    }

    /** Breaks every copy beyond MAX_COPIES of each printing (the finest ones kept): the collection after it. */
    public View breakExtras(String userId) {
        View current = of(userId);
        Map<String, String> rarities = pool.raritiesOf(current.cards().keySet());
        Criteria guard = Criteria.where("_id").is(userId);
        Update update = new Update();
        Map<String, Integer> gained = new LinkedHashMap<>();
        List<Criteria> enough = new ArrayList<>();
        current.cards().forEach((cardId, owned) -> {
            String rarity = rarities.get(cardId);
            if (rarity == null) return;
            CollectionRules.extras(owned).forEach((finish, extra) -> {
                String copies = field(cardId, finish);
                enough.add(Criteria.where(copies).gte(extra));
                update.inc(copies, -extra);
                gained.merge("fragments." + rarity, extra * CollectionRules.fragmentsOf(finish), Integer::sum);
            });
        });
        if (enough.isEmpty()) throw new CollectionException("No tienes copias sobrantes");
        gained.forEach(update::inc);
        Document after = mongo.findAndModify(Query.query(guard.andOperator(enough.toArray(Criteria[]::new))), update,
                FindAndModifyOptions.options().returnNew(true), Document.class, COLLECTION);
        if (after == null) throw new CollectionException("Tu colección cambió; inténtalo de nuevo");
        return view(after);
    }

    /** Sets an account's fragments of some rarities (C, UC, R, SR, SEC; never below zero). For testing. */
    public View setFragments(String userId, Map<String, Integer> fragments) {
        Update update = new Update();
        fragments.forEach((rarity, amount) -> {
            if (!BoosterRules.RARITIES.contains(rarity)) throw new CollectionException("Esa rareza no existe");
            update.set("fragments." + rarity, Math.max(0, amount == null ? 0 : amount));
        });
        if (!fragments.isEmpty()) mongo.upsert(byId(userId), update, COLLECTION);
        return of(userId);
    }

    /** How many copies of each of these cards (one id per copy, as a deck lists them) the player lacks, in total. */
    public int missing(String userId, List<String> deckCards) {
        Map<String, Integer> needed = new LinkedHashMap<>();
        for (String id : deckCards) needed.merge(id, 1, Integer::sum);
        Map<String, Map<String, Integer>> cards = of(userId).cards();
        int missing = 0;
        for (Map.Entry<String, Integer> e : needed.entrySet()) {
            int owned = cards.getOrDefault(e.getKey(), Map.of()).values().stream().mapToInt(Integer::intValue).sum();
            missing += Math.max(0, e.getValue() - owned);
        }
        return missing;
    }

    private Crafted spend(String userId, String cardId, List<String> finishes) {
        String rarity = rarity(cardId);
        int cost = finishes.size() * CollectionRules.CRAFT_COST;
        Update update = new Update().inc("fragments." + rarity, -cost);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String finish : finishes) counts.merge(field(cardId, finish), 1, Integer::sum);
        counts.forEach(update::inc);
        Document after = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(userId).and("fragments." + rarity).gte(cost)), update,
                FindAndModifyOptions.options().returnNew(true), Document.class, COLLECTION);
        if (after == null) throw new CollectionException("No te alcanzan los fragmentos");
        return new Crafted(finishes, view(after));
    }

    private String rarity(String cardId) {
        if (cardId == null || !CARD_ID.matcher(cardId).matches()) throw new CollectionException("Esa carta no existe");
        String rarity = pool.rarityOf(cardId);
        if (rarity == null) throw new CollectionException("Esa carta no se puede fabricar ni romper");
        return rarity;
    }

    private static String field(String cardId, String finish) {
        if (cardId == null || !CARD_ID.matcher(cardId).matches()) throw new CollectionException("Esa carta no existe");
        return "cards." + cardId + "." + finish;
    }

    /** The document as copies and fragments, leaving out what is at zero. */
    private static View view(Document doc) {
        Map<String, Map<String, Integer>> cards = new LinkedHashMap<>();
        Map<String, Integer> fragments = new LinkedHashMap<>();
        if (doc != null) {
            Document savedCards = doc.get("cards", Document.class);
            if (savedCards != null) {
                savedCards.forEach((id, value) -> {
                    if (!(value instanceof Document finishes)) return;
                    Map<String, Integer> owned = new LinkedHashMap<>();
                    finishes.forEach((finish, n) -> {
                        if (n instanceof Number number && number.intValue() > 0) owned.put(finish, number.intValue());
                    });
                    if (!owned.isEmpty()) cards.put(id, owned);
                });
            }
            Document savedFragments = doc.get("fragments", Document.class);
            if (savedFragments != null) {
                savedFragments.forEach((rarity, n) -> {
                    if (n instanceof Number number && number.intValue() > 0) fragments.put(rarity, number.intValue());
                });
            }
        }
        return new View(cards, fragments);
    }

    private static Query byId(String userId) {
        return Query.query(Criteria.where("_id").is(userId));
    }
}
