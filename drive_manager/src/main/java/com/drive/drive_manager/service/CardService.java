package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.repository.CardRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Service class for Card CRUD operations.
 */
@Service
public class CardService {

    @Autowired
    private CardRepository cardRepository;

    /**
     * Create a new card.
     */
    public Card createCard(Card card) {
        return cardRepository.save(card);
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
            if (cardDetails.getSpecialCost() != null) {
                card.setSpecialCost(cardDetails.getSpecialCost());
            }
            if (cardDetails.getSpecialSummonKind() != null) {
                card.setSpecialSummonKind(cardDetails.getSpecialSummonKind());
            }
            if (cardDetails.getRequirement() != null) {
                card.setRequirement(cardDetails.getRequirement());
            }

            return Optional.of(cardRepository.save(card));
        }
        return Optional.empty();
    }

    /**
     * Delete a card by ID.
     */
    public boolean deleteCard(String id) {
        if (cardRepository.existsById(id)) {
            cardRepository.deleteById(id);
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
}
