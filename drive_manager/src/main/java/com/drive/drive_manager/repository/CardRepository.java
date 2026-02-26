package com.drive.drive_manager.repository;

import com.drive.drive_manager.dto.Card;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * MongoDB repository for Card entities.
 * Extends MongoRepository to provide CRUD operations.
 */
@Repository
public interface CardRepository extends MongoRepository<Card, String> {

    /**
     * Find all cards by card name (case-insensitive).
     */
    List<Card> findByCardNameIgnoreCase(String cardName);

    /**
     * Find all cards by card type.
     */
    List<Card> findByCardType(String cardType);

    /**
     * Find all cards by edition.
     */
    List<Card> findByEdition(String edition);

    /**
     * Find all cards by regulation.
     */
    List<Card> findByRegulation(String regulation);

    /**
     * Find all cards that contain a specific class in cardClasses list.
     */
    List<Card> findByCardClassesContaining(String cardClass);

    /**
     * Find all starter cards.
     */
    List<Card> findByStarterTrue();

}
