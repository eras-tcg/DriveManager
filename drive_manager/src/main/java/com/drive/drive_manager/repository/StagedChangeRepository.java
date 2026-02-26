package com.drive.drive_manager.repository;

import com.drive.drive_manager.dto.StagedChange;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface StagedChangeRepository extends MongoRepository<StagedChange, String> {
}
