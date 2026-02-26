package com.drive.drive_manager.controller;

import com.drive.drive_manager.service.DriveParser;
import com.drive.drive_manager.service.DriveParser.CardsResponse;
import com.drive.drive_manager.service.DriveParser.SyncResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * REST controller for Google Drive operations.
 */
@RestController
@RequestMapping("/api/drive")
public class DriveController {

    @Autowired
    private DriveParser driveParser;

    /**
     * Health check endpoint.
     * GET /api/drive/health
     */
    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("DriveController is healthy");
    }

    /**
     * Full drive listing without persistence.
     * GET /api/drive/cards?folderUrl=...
     */
    @GetMapping("/cards")
    public ResponseEntity<CardsResponse> readCards(
            @RequestParam(required = false) String folderUrl) {
        try {
            CardsResponse response = driveParser.listCards(folderUrl);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Filtered drive fetch with MongoDB persistence.
     *
     * POST /api/drive/cards/map_to_db
     *
     * Body (all fields optional, omit or send empty list to skip that filter):
     * {
     *   "editions":    ["ST1", "E1"],   // folder names; empty = all editions
     *   "subEditions": ["MAIN", "SUB1"],// section names; empty = all sections
     *   "colors":      ["B", "G"]       // color letters; empty = all colors
     * }
     *
     * Rules:
     *  - editions + subEditions both empty → parse whole drive
     *  - colors empty → parse all colors
     *  - ST entries in editions: no color subfolders; color is matched from filename
     *  - E entries in editions: traverses section → color subfolders normally
     */
    @PostMapping("/cards/map_to_db")
    public ResponseEntity<SyncResult> syncCards(
            @RequestBody(required = false) SyncRequest body) {
        SyncRequest req = body != null ? body : new SyncRequest(List.of(), List.of(), List.of());
        try {
            SyncResult result = driveParser.syncCards(
                    req.editions() != null ? req.editions() : List.of(),
                    req.subEditions() != null ? req.subEditions() : List.of(),
                    req.colors() != null ? req.colors() : List.of()
            );
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    public record SyncRequest(
            List<String> editions,
            List<String> subEditions,
            List<String> colors
    ) {}
}
