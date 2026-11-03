package com.drive.drive_manager.repository;

import com.drive.drive_manager.dto.ShopOdds;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ShopOddsRepository extends MongoRepository<ShopOdds, String> {}
