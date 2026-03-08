package com.drive.drive_manager.repository;

import com.drive.drive_manager.dto.CardRefData;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CardRefDataRepository extends MongoRepository<CardRefData, String> {}
