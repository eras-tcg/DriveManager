package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.Effect;
import com.drive.drive_manager.repository.CardRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Service class for Card CRUD operations.
 */
@Service
public class CardService {

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private CardNames cardNames;

    @Autowired
    private com.drive.drive_manager.repository.DriveCardRepository driveCards;

    /**
     * Create a new card.
     */
    public Card createCard(Card card) {
        // Its art is set through CardArtService only
        card.setArtKey(null);
        card.setArtType(null);
        card.setRenderKey(null);
        card.setShineKey(null);
        card.setCreatureKey(null);
        checkCode(card, null);
        Card saved = cardRepository.save(card);
        // Its image takes the printed name (the file name has no real hyphens)
        cardNames.syncImages(saved);
        return saved;
    }

    /**
     * Get card by ID.
     */
    public Optional<Card> getCardById(String id) {
        return cardRepository.findById(id);
    }

    /**
     * Get all cards.
     */
    public List<Card> getAllCards() {
        return cardRepository.findAll();
    }

    /**
     * Update an existing card.
     */
    public Optional<Card> updateCard(String id, Card cardDetails) {
        Optional<Card> existingCard = cardRepository.findById(id);
        if (existingCard.isPresent()) {
            Card card = existingCard.get();

            if (cardDetails.getCardName() != null) {
                card.setCardName(cardDetails.getCardName());
            }
            if (cardDetails.getCardType() != null) {
                card.setCardType(cardDetails.getCardType());
            }
            if (cardDetails.getStrength() != null) {
                card.setStrength(cardDetails.getStrength());
            }
            if (cardDetails.getCost() != null) {
                card.setCost(cardDetails.getCost());
            }
            if (cardDetails.getLevel() != null) {
                card.setLevel(cardDetails.getLevel());
            }
            if (cardDetails.getStarter() != null) {
                card.setStarter(cardDetails.getStarter());
            }
            if (cardDetails.getCardClasses() != null) {
                card.setCardClasses(cardDetails.getCardClasses());
            }
            if (cardDetails.getEdition() != null) {
                card.setEdition(cardDetails.getEdition());
            }
            if (cardDetails.getColorIdentity() != null) {
                card.setColorIdentity(cardDetails.getColorIdentity());
            }
            if (cardDetails.getCardNumber() != null) {
                card.setCardNumber(cardDetails.getCardNumber());
            }
            if (cardDetails.getRegulation() != null) {
                card.setRegulation(cardDetails.getRegulation());
            }
            if (cardDetails.getEffects() != null) {
                card.setEffects(cardDetails.getEffects());
            }
            if (cardDetails.getInheritEffects() != null) {
                card.setInheritEffects(cardDetails.getInheritEffects());
            }
            if (cardDetails.getKeywordEffects() != null) {
                card.setKeywordEffects(cardDetails.getKeywordEffects());
            }
            if (cardDetails.getInheritKeywordEffects() != null) {
                card.setInheritKeywordEffects(cardDetails.getInheritKeywordEffects());
            }
            if (cardDetails.getSpecialCost() != null) {
                card.setSpecialCost(cardDetails.getSpecialCost());
            }
            if (cardDetails.getSpecialSummonKind() != null) {
                card.setSpecialSummonKind(cardDetails.getSpecialSummonKind());
            }
            if (cardDetails.getRequirement() != null) {
                card.setRequirement(cardDetails.getRequirement());
            }
            if (cardDetails.getRarity() != null) {
                card.setRarity(cardDetails.getRarity());
            }
            if (cardDetails.getColors() != null) {
                card.setColors(cardDetails.getColors());
            }
            if (cardDetails.getSubEdition() != null) {
                card.setSubEdition(cardDetails.getSubEdition().isBlank() ? null : cardDetails.getSubEdition());
            }
            if (cardDetails.getEnlace() != null) {
                card.setEnlace(cardDetails.getEnlace());
            }
            if (cardDetails.getArrows() != null) {
                card.setArrows(cardDetails.getArrows());
            }
            if (cardDetails.getFormationEffects() != null) {
                card.setFormationEffects(cardDetails.getFormationEffects());
            }
            if (cardDetails.getFormationKeywordEffects() != null) {
                card.setFormationKeywordEffects(cardDetails.getFormationKeywordEffects());
            }
            if (cardDetails.getDesign() != null) {
                card.setDesign(cardDetails.getDesign());
            }
            if (cardDetails.getDeckCopies() != null) {
                if (cardDetails.getDeckCopies() < 0) throw new IllegalArgumentException("Las copias no pueden ser negativas");
                card.setDeckCopies(cardDetails.getDeckCopies());
            }
            checkCode(card, id);

            Card saved = cardRepository.save(card);
            cardNames.syncImages(saved);
            return Optional.of(saved);
        }
        return Optional.empty();
    }

    /** A code (edition, sub-edition, color and number) used by another card. */
    public static class CodeTaken extends RuntimeException {
        public CodeTaken(String message) {
            super(message);
        }
    }

    /** Refuses a code another card has (cards without number are not checked). */
    void checkCode(Card card, String selfId) {
        if (card.getEdition() == null || card.getCardNumber() == null || card.getColorIdentity() == null) return;
        boolean taken = cardRepository.findByEdition(card.getEdition()).stream()
                .anyMatch(c -> sameCode(c, card) && !c.getId().equals(selfId));
        if (taken) throw new CodeTaken("Ya existe una carta con el código " + code(card));
    }

    /** "E1-R03", "E1.1-P01": how a code is shown. */
    static String code(Card c) {
        String sub = blank(c.getSubEdition()) ? "" : "." + c.getSubEdition();
        return c.getEdition() + sub + "-" + c.getColorIdentity().toUpperCase() + String.format("%02d", c.getCardNumber());
    }

    static boolean sameCode(Card a, Card b) {
        return java.util.Objects.equals(a.getEdition(), b.getEdition())
                && java.util.Objects.equals(blank(a.getSubEdition()) ? null : a.getSubEdition(), blank(b.getSubEdition()) ? null : b.getSubEdition())
                && a.getColorIdentity() != null && a.getColorIdentity().equalsIgnoreCase(b.getColorIdentity())
                && java.util.Objects.equals(a.getCardNumber(), b.getCardNumber());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Delete a card by ID.
     */
    public boolean deleteCard(String id) {
        if (cardRepository.existsById(id)) {
            cardRepository.deleteById(id);
            // A card made in the editor takes its image record with it
            if (driveCards != null) driveCards.deleteById(com.drive.drive_manager.dto.DriveCard.WEB_PREFIX + id);
            return true;
        }
        return false;
    }

    /**
     * Find cards by name.
     */
    public List<Card> findCardsByName(String cardName) {
        return cardRepository.findByCardNameIgnoreCase(cardName);
    }

    /**
     * Find cards by type.
     */
    public List<Card> findCardsByType(String cardType) {
        return cardRepository.findByCardType(cardType);
    }

    /**
     * Find cards by edition.
     */
    public List<Card> findCardsByEdition(String edition) {
        return cardRepository.findByEdition(edition);
    }

    /**
     * Find cards by regulation.
     */
    public List<Card> findCardsByRegulation(String regulation) {
        return cardRepository.findByRegulation(regulation);
    }

    /**
     * Find cards by card class.
     */
    public List<Card> findCardsByClass(String cardClass) {
        return cardRepository.findByCardClassesContaining(cardClass);
    }

    /**
     * Find all starter cards.
     */
    public List<Card> findStarterCards() {
        return cardRepository.findByStarterTrue();
    }

    /**
     * Returns summary info for every card that uses the given ref value in the
     * specified field (classes / instances / kinds / tags).
     */
    public List<Map<String, Object>> findCardsUsingRefValue(String field, String value) {
        return cardRepository.findAll().stream()
                .filter(c -> cardUsesValue(c, field, value))
                .map(c -> Map.<String, Object>of(
                        "id",         c.getId()         != null ? c.getId()         : "",
                        "cardName",   c.getCardName()   != null ? c.getCardName()   : "",
                        "edition",    c.getEdition()    != null ? c.getEdition()    : "",
                        "cardNumber", c.getCardNumber() != null ? c.getCardNumber() : 0
                ))
                .toList();
    }

    /**
     * Strips the given ref value from every affected card and persists the changes.
     */
    public void removeRefValueFromAllCards(String field, String value) {
        replaceRefValueInAllCards(field, value, null);
    }

    /**
     * Replaces oldValue with newValue in every affected card.
     * If newValue is null the value is removed (equivalent to removeRefValueFromAllCards).
     * For list fields (classes, tags) the item is swapped in-place or removed.
     * For scalar fields (instances, kinds) the field is set to newValue (or null).
     */
    public void replaceRefValueInAllCards(String field, String oldValue, String newValue) {
        cardRepository.findAll().stream()
                .filter(c -> cardUsesValue(c, field, oldValue))
                .forEach(card -> {
                    switch (field) {
                        case "classes" -> {
                            if (card.getCardClasses() != null) {
                                int idx = card.getCardClasses().indexOf(oldValue);
                                if (idx >= 0) {
                                    if (newValue != null) card.getCardClasses().set(idx, newValue);
                                    else card.getCardClasses().remove(idx);
                                }
                            }
                        }
                        case "instances" -> effectStream(card)
                                .filter(e -> oldValue.equals(e.getInstance()))
                                .forEach(e -> e.setInstance(newValue));
                        case "kinds" -> effectStream(card)
                                .filter(e -> oldValue.equals(e.getKind()))
                                .forEach(e -> e.setKind(newValue));
                        case "tags" -> effectStream(card)
                                .filter(e -> e.getTags() != null)
                                .forEach(e -> {
                                    int idx = e.getTags().indexOf(oldValue);
                                    if (idx >= 0) {
                                        if (newValue != null) e.getTags().set(idx, newValue);
                                        else e.getTags().remove(idx);
                                    }
                                });
                    }
                    cardRepository.save(card);
                });
    }

    private boolean cardUsesValue(Card card, String field, String value) {
        return switch (field) {
            case "classes"   -> card.getCardClasses() != null && card.getCardClasses().contains(value);
            case "instances" -> effectStream(card).anyMatch(e -> value.equals(e.getInstance()));
            case "kinds"     -> effectStream(card).anyMatch(e -> value.equals(e.getKind()));
            case "tags"      -> effectStream(card).anyMatch(e -> e.getTags() != null && e.getTags().contains(value));
            default          -> false;
        };
    }

    private Stream<Effect> effectStream(Card card) {
        Stream<Effect> s1 = card.getEffects()        != null ? card.getEffects().stream()        : Stream.empty();
        Stream<Effect> s2 = card.getInheritEffects() != null ? card.getInheritEffects().stream() : Stream.empty();
        return Stream.concat(s1, s2);
    }
}
