package com.drive.drive_manager.service;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A player's preferences for the site and the simulator's table (theme, table style, hand height…), kept with their
 * account so they follow them to any device. Each one is a key and a small value (text, number or yes/no); the page
 * decides what they mean. One document per user in {@code user_preferences}.
 */
@Service
public class PreferencesService {

    static final String COLLECTION = "user_preferences";
    /** At most this many preferences per player. */
    public static final int MAX_KEYS = 64;
    /** A text value's longest length. */
    public static final int MAX_TEXT = 64;
    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    private final MongoTemplate mongo;

    public PreferencesService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** The player's preferences (empty when they never saved any). */
    public Map<String, Object> of(String userId) {
        Document doc = mongo.findById(userId, Document.class, COLLECTION);
        Object prefs = doc == null ? null : doc.get("prefs");
        Map<String, Object> out = new LinkedHashMap<>();
        if (prefs instanceof Map<?, ?> map) map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    /**
     * Replaces the player's preferences with these, once checked: keys of lowercase letters, digits and dashes, values
     * text (short), numbers or yes/no. Anything else is refused whole (IllegalArgumentException, with why).
     */
    public Map<String, Object> put(String userId, Map<String, Object> prefs) {
        Map<String, Object> clean = check(prefs);
        mongo.upsert(Query.query(Criteria.where("_id").is(userId)),
                Update.update("prefs", clean).set("updatedAt", Instant.now()), COLLECTION);
        return clean;
    }

    static Map<String, Object> check(Map<String, Object> prefs) {
        if (prefs == null) throw new IllegalArgumentException("Faltan las preferencias");
        if (prefs.size() > MAX_KEYS) throw new IllegalArgumentException("Demasiadas preferencias");
        Map<String, Object> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : prefs.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (key == null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("Preferencia no válida: " + key);
            if (value instanceof String text) {
                if (text.length() > MAX_TEXT) throw new IllegalArgumentException("Valor demasiado largo: " + key);
            } else if (!(value instanceof Number) && !(value instanceof Boolean)) {
                throw new IllegalArgumentException("Valor no válido: " + key);
            }
            clean.put(key, value);
        }
        return clean;
    }
}
