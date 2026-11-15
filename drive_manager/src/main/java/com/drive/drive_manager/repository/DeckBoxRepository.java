package com.drive.drive_manager.repository;

import com.drive.drive_manager.dto.DeckBox;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface DeckBoxRepository extends MongoRepository<DeckBox, String> {
    List<DeckBox> findByUserIdOrderByOrderAsc(String userId);

    /** Deckboxes that hold a deck (deck_ids contains it). */
    List<DeckBox> findByDeckIds(String deckId);

    long countByUserId(String userId);
}
