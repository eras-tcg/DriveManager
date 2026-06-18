package com.drive.drive_manager.repository;

import com.drive.drive_manager.dto.GameNote;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface GameNoteRepository extends MongoRepository<GameNote, String> {
    List<GameNote> findAllByOrderByCreatedAtDesc();
}
