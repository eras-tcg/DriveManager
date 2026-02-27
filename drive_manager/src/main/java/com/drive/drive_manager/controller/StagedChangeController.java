package com.drive.drive_manager.controller;

import com.drive.drive_manager.dto.StagedChange;
import com.drive.drive_manager.service.StagedChangeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Manages staged Drive changes before they are applied to drive_cards and uploaded to R2.
 *
 * GET    /api/drive/staged           → list all pending staged changes
 * POST   /api/drive/staged/apply     → apply all staged changes
 * POST   /api/drive/staged/apply/{fileId} → apply one staged change
 * DELETE /api/drive/staged/{fileId}  → discard one staged change
 */
@RestController
@RequestMapping("/api/drive/staged")
public class StagedChangeController {

    private static final Logger logger = LoggerFactory.getLogger(StagedChangeController.class);

    private final StagedChangeService stagedChangeService;

    public StagedChangeController(StagedChangeService stagedChangeService) {
        this.stagedChangeService = stagedChangeService;
    }

    @GetMapping
    public ResponseEntity<List<StagedChange>> list() {
        return ResponseEntity.ok(stagedChangeService.listAll());
    }

    @PostMapping("/apply")
    public ResponseEntity<Map<String, Object>> applyAll() {
        try {
            int count = stagedChangeService.applyAll();
            return ResponseEntity.ok(Map.of("applied", count));
        } catch (Exception e) {
            logger.error("Failed to apply all staged changes", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    @PostMapping("/apply/{fileId}")
    public ResponseEntity<Map<String, Object>> applyOne(@PathVariable String fileId) {
        try {
            stagedChangeService.applyOne(fileId);
            return ResponseEntity.ok(Map.of("applied", fileId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Failed to apply staged change for fileId: {}", fileId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    @DeleteMapping("/{fileId}")
    public ResponseEntity<Void> discard(@PathVariable String fileId) {
        stagedChangeService.discard(fileId);
        return ResponseEntity.noContent().build();
    }
}
