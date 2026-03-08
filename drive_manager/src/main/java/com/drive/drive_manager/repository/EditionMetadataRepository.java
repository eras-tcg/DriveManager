package com.drive.drive_manager.repository;

import com.drive.drive_manager.dto.EditionMetadata;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EditionMetadataRepository extends MongoRepository<EditionMetadata, String> {
}
