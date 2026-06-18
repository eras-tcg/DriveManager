package com.drive.drive_manager.repository;

import com.drive.drive_manager.dto.Game;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface GameRepository extends MongoRepository<Game, String> {
    List<Game> findAllByOrderByCreatedAtDesc();
}
