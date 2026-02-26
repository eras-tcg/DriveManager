package com.drive.drive_manager.repository;

import com.drive.drive_manager.dto.SyncState;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SyncStateRepository extends MongoRepository<SyncState, String> {
}
