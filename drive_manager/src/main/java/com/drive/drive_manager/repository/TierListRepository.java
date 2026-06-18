package com.drive.drive_manager.repository;

import com.drive.drive_manager.dto.TierList;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface TierListRepository extends MongoRepository<TierList, String> {}
