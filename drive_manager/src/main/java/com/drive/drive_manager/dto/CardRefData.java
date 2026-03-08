package com.drive.drive_manager.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reference / vocabulary lists for card editing UI.
 * Stored as a single document with id="default".
 *
 * classes       — card class labels (Warrior, Dragon, …)
 * instances     — effect instance labels (Quick, Continuous, …)
 * kinds         — effect kind labels (attack, draw, …)
 * tags          — effect tag labels; users can add new ones from the UI
 * instanceKinds — optional instance → kind mapping for auto-fill in the card editor
 */
@Data
@NoArgsConstructor
@Document(collection = "card_ref_data")
public class CardRefData {
    @Id
    private String id;
    private List<String> classes;
    private List<String> instances;
    private List<String> kinds;
    private List<String> tags;
    private Map<String, String> instanceKinds = new HashMap<>();
    private Map<String, KeywordEffect> keywordEffects = new HashMap<>();

    public CardRefData(String id, List<String> classes, List<String> instances,
                       List<String> kinds, List<String> tags, Map<String, KeywordEffect> keywordEffects) {
        this.id = id;
        this.classes = classes;
        this.instances = instances;
        this.kinds = kinds;
        this.tags = tags;
        this.instanceKinds = new HashMap<>();
        this.keywordEffects = keywordEffects != null ? keywordEffects : new HashMap<>();
    }
}
