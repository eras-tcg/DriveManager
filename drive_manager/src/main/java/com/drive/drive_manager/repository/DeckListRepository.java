package com.drive.drive_manager.repository;

import com.drive.drive_manager.dto.DeckList;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface DeckListRepository extends MongoRepository<DeckList, String> {
}
