package com.drive.drive_manager.repository;

import com.drive.drive_manager.dto.HomeContent;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FrontContentRepository extends MongoRepository<HomeContent, String> {}
