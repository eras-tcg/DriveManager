package com.drive.drive_manager.repository;

import com.drive.drive_manager.dto.Newsletter;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface NewsletterRepository extends MongoRepository<Newsletter, String> {
    List<Newsletter> findAllByOrderByPublishedAtDesc();
}
