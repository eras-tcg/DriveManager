package com.drive.drive_manager.repository;

import com.drive.drive_manager.dto.SyncReport;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SyncReportRepository extends MongoRepository<SyncReport, String> {
}
