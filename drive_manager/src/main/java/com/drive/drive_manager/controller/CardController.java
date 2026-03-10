package com.drive.drive_manager.controller;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.CardRefData;
import com.drive.drive_manager.dto.Effect;
import com.drive.drive_manager.repository.CardRefDataRepository;
import com.drive.drive_manager.service.CardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.drive.drive_manager.dto.KeywordEffect;

/**
 * REST controller for Card CRUD operations.
 */
@RestController
@RequestMapping("/api/cards")
public class CardController {

    @Autowired
    private CardService cardService;

    @Autowired
    private CardRefDataRepository refRepo;

    private static final String REF_ID = "default";

    /**
     * Create a new card.
     * POST /api/cards
     */
    @PostMapping
    public ResponseEntity<Card> createCard(@RequestBody Card card) {
        Card createdCard = cardService.createCard(card);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdCard);
    }

    /**
     * Get card by ID.
     * GET /api/cards/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<Card> getCardById(@PathVariable String id) {
        Optional<Card> card = cardService.getCardById(id);
        return card.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Get all cards.
     * GET /api/cards
     */
    @GetMapping
    public ResponseEntity<List<Card>> getAllCards() {
        List<Card> cards = cardService.getAllCards();
        return ResponseEntity.ok(cards);
    }

    /**
     * Update a card by ID.
     * PUT /api/cards/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<Card> updateCard(@PathVariable String id, @RequestBody Card cardDetails) {
        Optional<Card> updatedCard = cardService.updateCard(id, cardDetails);
        return updatedCard.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Delete a card by ID.
     * DELETE /api/cards/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCard(@PathVariable String id) {
        boolean deleted = cardService.deleteCard(id);
        if (deleted) {
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Find cards by name.
     * GET /api/cards/search/name?cardName=...
     */
    @GetMapping("/search/name")
    public ResponseEntity<List<Card>> findCardsByName(@RequestParam String cardName) {
        List<Card> cards = cardService.findCardsByName(cardName);
        return ResponseEntity.ok(cards);
    }

    /**
     * Find cards by type.
     * GET /api/cards/search/type?cardType=...
     */
    @GetMapping("/search/type")
    public ResponseEntity<List<Card>> findCardsByType(@RequestParam String cardType) {
        List<Card> cards = cardService.findCardsByType(cardType);
        return ResponseEntity.ok(cards);
    }

    /**
     * Find cards by edition.
     * GET /api/cards/search/edition?edition=...
     */
    @GetMapping("/search/edition")
    public ResponseEntity<List<Card>> findCardsByEdition(@RequestParam String edition) {
        List<Card> cards = cardService.findCardsByEdition(edition);
        return ResponseEntity.ok(cards);
    }

    /**
     * Find cards by regulation.
     * GET /api/cards/search/regulation?regulation=...
     */
    @GetMapping("/search/regulation")
    public ResponseEntity<List<Card>> findCardsByRegulation(@RequestParam String regulation) {
        List<Card> cards = cardService.findCardsByRegulation(regulation);
        return ResponseEntity.ok(cards);
    }

    /**
     * Find cards by card class.
     * GET /api/cards/search/class?cardClass=...
     */
    @GetMapping("/search/class")
    public ResponseEntity<List<Card>> findCardsByClass(@RequestParam String cardClass) {
        List<Card> cards = cardService.findCardsByClass(cardClass);
        return ResponseEntity.ok(cards);
    }

    /**
     * Find all starter cards.
     * GET /api/cards/search/starter
     */
    @GetMapping("/search/starter")
    public ResponseEntity<List<Card>> findStarterCards() {
        List<Card> cards = cardService.findStarterCards();
        return ResponseEntity.ok(cards);
    }

    /**
     * Multi-filter search.
     * GET /api/cards/search?edition=E1&cardType=Creature&cardClass=Warrior&starter=true
     * All params optional; omit to skip that filter.
     */
    @GetMapping("/search")
    public ResponseEntity<List<Card>> search(
            @RequestParam(required = false) String edition,
            @RequestParam(required = false) String cardType,
            @RequestParam(required = false) String cardClass,
            @RequestParam(required = false) Boolean starter) {

        List<Card> result = cardService.getAllCards().stream()
                .filter(c -> edition   == null || edition.equalsIgnoreCase(c.getEdition()))
                .filter(c -> cardType  == null || cardType.equalsIgnoreCase(c.getCardType()))
                .filter(c -> cardClass == null || (c.getCardClasses() != null && c.getCardClasses().stream().anyMatch(cardClass::equalsIgnoreCase)))
                .filter(c -> starter   == null || starter.equals(c.getStarter()))
                .toList();

        return ResponseEntity.ok(result);
    }

    // ── Reference data (vocabulary lists for the UI) ─────────────────────────

    /**
     * GET /api/cards/ref — returns the vocabulary document (creates a blank one if absent).
     */
    @GetMapping("/ref")
    public ResponseEntity<CardRefData> getRef() {
        try {
            CardRefData data = refRepo.findById(REF_ID).orElseGet(() ->
                    new CardRefData(REF_ID, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new HashMap<>()));
            
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            // If conversion fails, delete and recreate
            try {
                refRepo.deleteById(REF_ID);
            } catch (Exception ignored) {}
            return ResponseEntity.ok(
                    new CardRefData(REF_ID, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new HashMap<>())
            );
        }
    }

    /**
     * PUT /api/cards/ref — replace the full vocabulary document.
     */
    @PutMapping("/ref")
    public ResponseEntity<CardRefData> updateRef(@RequestBody CardRefData body) {
        body.setId(REF_ID);
        return ResponseEntity.ok(refRepo.save(body));
    }

    /**
     * POST /api/cards/ref/tags — kept for backwards compat with Cards.vue effect editor.
     * Body: { "value": "new tag" }
     */
    @PostMapping("/ref/tags")
    public ResponseEntity<Object> addTag(@RequestBody Map<String, Object> body) {
        return addRefItem("tags", body);
    }

    /**
     * POST /api/cards/ref/{field} — add an item to classes / instances / kinds / tags / keywordsEffects.
     * For keywordsEffects: Body: { "keyword": "...", "effect": { ... } }
     * For others: Body: { "value": "..." }
     */
    @PostMapping("/ref/{field}")
    public ResponseEntity<Object> addRefItem(@PathVariable String field,
                                             @RequestBody Map<String, Object> body) {
        CardRefData ref = getOrCreateRef();
        if ("keywordEffects".equals(field)) {
            String keyword = (String) body.get("keyword");
            if (keyword == null || keyword.isBlank())
                return ResponseEntity.badRequest().body(Map.of("error", "keyword is required"));

            if (!body.containsKey("effect"))
                return ResponseEntity.badRequest().body(Map.of("error", "effect is required"));

            @SuppressWarnings("unchecked")
            Map<String, Object> effectData = (Map<String, Object>) body.get("effect");
            Effect fullEffect = convertMapToEffect(effectData);
            KeywordEffect effect = new KeywordEffect(keyword.trim(), fullEffect);
            
            ref.getKeywordEffects().put(keyword.trim(), effect);
            refRepo.save(ref);
            return ResponseEntity.ok(ref);
        } else {
            String value = (String) body.get("value");
            if (value == null || value.isBlank())
                return ResponseEntity.badRequest().body(Map.of("error", "value is required"));
            List<String> list = refField(ref, field);
            if (list == null)
                return ResponseEntity.badRequest().body(Map.of("error", "unknown field: " + field));
            String trimmed = value.trim();
            if (!list.contains(trimmed)) { list.add(trimmed); refRepo.save(ref); }
            return ResponseEntity.ok(ref);
        }
    }

    /**
     * DELETE /api/cards/ref/{field} — remove an item from classes / instances / kinds / tags / keywordsEffects.
     * For keywordsEffects: Body: { "keyword": "..." }
     * For others: Body: { "value": "..." }
     */
    @DeleteMapping("/ref/{field}")
    public ResponseEntity<Object> deleteRefItem(@PathVariable String field,
                                                @RequestBody Map<String, Object> body) {
        CardRefData ref = getOrCreateRef();
        if ("keywordEffects".equals(field)) {
            String keyword = (String) body.get("keyword");
            if (keyword == null || keyword.isBlank())
                return ResponseEntity.badRequest().body(Map.of("error", "keyword is required"));
            ref.getKeywordEffects().remove(keyword.trim());
            refRepo.save(ref);
            return ResponseEntity.ok(ref);
        } else {
            String value = (String) body.get("value");
            if (value == null || value.isBlank())
                return ResponseEntity.badRequest().body(Map.of("error", "value is required"));
            List<String> list = refField(ref, field);
            if (list == null)
                return ResponseEntity.badRequest().body(Map.of("error", "unknown field: " + field));
            list.remove(value.trim());
            refRepo.save(ref);
            return ResponseEntity.ok(ref);
        }
    }

    /**
     * GET /api/cards/ref/{field}/check?value=... — lists cards that use the value.
     * Response: { "count": N, "cards": [ { id, cardName, edition, cardNumber }, … ] }
     */
    @GetMapping("/ref/{field}/check")
    public ResponseEntity<Map<String, Object>> checkRefUsage(@PathVariable String field,
                                                              @RequestParam String value) {
        List<Map<String, Object>> cards = cardService.findCardsUsingRefValue(field, value);
        return ResponseEntity.ok(Map.of("count", cards.size(), "cards", cards));
    }

    /**
     * POST /api/cards/ref/{field}/replace — replaces oldValue with replacement in all cards, then removes old from ref.
     * Body: { "value": "...", "replacement": "..." }  (omit replacement or send blank/null to remove)
     */
    @PostMapping("/ref/{field}/replace")
    public ResponseEntity<Object> replaceRefItem(@PathVariable String field,
                                                 @RequestBody Map<String, String> body) {
        String value = body.get("value");
        String replacement = body.get("replacement");
        if (value == null || value.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "value is required"));
        String replacementTrimmed = (replacement != null && !replacement.isBlank()) ? replacement.trim() : null;
        cardService.replaceRefValueInAllCards(field, value.trim(), replacementTrimmed);
        CardRefData ref = getOrCreateRef();
        List<String> list = refField(ref, field);
        if (list != null) { list.remove(value.trim()); refRepo.save(ref); }
        return ResponseEntity.ok(ref);
    }

    /**
     * POST /api/cards/ref/{field}/purge — removes the value from all cards, then from ref data.
     * Body: { "value": "..." }
     */
    @PostMapping("/ref/{field}/purge")
    public ResponseEntity<Object> purgeRefItem(@PathVariable String field,
                                               @RequestBody Map<String, Object> body) {
        String value = (String) body.get("value");
        if (value == null || value.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "value is required"));
        cardService.removeRefValueFromAllCards(field, value.trim());
        CardRefData ref = getOrCreateRef();
        List<String> list = refField(ref, field);
        if (list != null) { list.remove(value.trim()); refRepo.save(ref); }
        return ResponseEntity.ok(ref);
    }

    /**
     * PATCH /api/cards/ref/instance-kind — set or clear the default kind for an instance.
     * Body: { "instance": "Quick", "kind": "attack" }  (omit kind or send blank to remove)
     */
    @PatchMapping("/ref/instance-kind")
    public ResponseEntity<Object> setInstanceKind(@RequestBody Map<String, String> body) {
        String instance = body.get("instance");
        String kind     = body.get("kind");
        if (instance == null || instance.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "instance is required"));
        CardRefData ref = getOrCreateRef();
        if (ref.getInstanceKinds() == null) ref.setInstanceKinds(new java.util.HashMap<>());
        if (kind == null || kind.isBlank()) ref.getInstanceKinds().remove(instance.trim());
        else                               ref.getInstanceKinds().put(instance.trim(), kind.trim());
        refRepo.save(ref);
        return ResponseEntity.ok(ref);
    }

    private CardRefData getOrCreateRef() {
        return refRepo.findById(REF_ID)
                .orElseGet(() -> new CardRefData(REF_ID,
                        new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new HashMap<>()));
    }

    private List<String> refField(CardRefData ref, String field) {
        return switch (field) {
            case "classes"   -> ref.getClasses();
            case "instances" -> ref.getInstances();
            case "kinds"     -> ref.getKinds();
            case "tags"      -> ref.getTags();
            default          -> null;
        };
    }
    
    @SuppressWarnings("unchecked")
    private Effect convertMapToEffect(Map<String, Object> effectData) {
        Effect effect = new Effect();
        effect.setInstance((String) effectData.get("instance"));
        effect.setUssageLimit((String) effectData.get("ussageLimit"));
        effect.setKind((String) effectData.get("kind"));
        effect.setTags((List<String>) effectData.get("tags"));
        
        List<Map<String, Object>> effectBlocksData = (List<Map<String, Object>>) effectData.get("effectBlocks");
        if (effectBlocksData != null) {
            List<Effect.effectBlock> effectBlocks = new ArrayList<>();
            for (Map<String, Object> blockData : effectBlocksData) {
                Effect.effectBlock block = new Effect.effectBlock();
                block.setActivationCondition((String) blockData.get("activationCondition"));
                block.setCost((String) blockData.get("cost"));
                block.setResolution((String) blockData.get("resolution"));
                effectBlocks.add(block);
            }
            effect.setEffectBlocks(effectBlocks);
        }
        
        return effect;
    }
}
