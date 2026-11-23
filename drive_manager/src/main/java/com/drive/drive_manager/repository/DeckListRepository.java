package com.drive.drive_manager.repository;

import com.drive.drive_manager.dto.DeckList;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface DeckListRepository extends MongoRepository<DeckList, String> {
    List<DeckList> findByUserId(String userId);

    /** The decks the bot may play in ranked Modo solo. */
    List<DeckList> findByBotDeckTrue();

    default List<DeckList> findPublicDecks() {
        return findAll().stream()
                .filter(deck -> !deck.isPrivateDeck())
                .toList();
    }
}
