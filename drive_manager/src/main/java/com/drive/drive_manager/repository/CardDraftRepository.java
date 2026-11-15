package com.drive.drive_manager.repository;

import com.drive.drive_manager.dto.CardDraft;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Drafts of the card editor (collection card_drafts). */
@Repository
public interface CardDraftRepository extends MongoRepository<CardDraft, String> {

    Optional<CardDraft> findFirstByMetaId(String metaId);
}
