package com.drive.drive_manager.repository;

import com.drive.drive_manager.dto.DriveCard;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DriveCardRepository extends MongoRepository<DriveCard, String> {
}
