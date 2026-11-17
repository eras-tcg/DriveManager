package com.drive.drive_manager.repository;

import com.drive.drive_manager.dto.DriveCard;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DriveCardRepository extends MongoRepository<DriveCard, String> {

    List<DriveCard> findByEditionAndNumber(String edition, Integer number);

    List<DriveCard> findByEdition(String edition);

    /** The images whose edition matches the pattern (the testing editions, {@code TestCards}). */
    List<DriveCard> findByEditionRegex(String regex);
}
