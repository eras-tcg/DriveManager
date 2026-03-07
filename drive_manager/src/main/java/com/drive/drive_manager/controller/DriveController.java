package com.drive.drive_manager.controller;

import com.drive.drive_manager.repository.DriveCardRepository;
import com.drive.drive_manager.service.DriveParser;
import com.drive.drive_manager.service.DriveParser.CardsResponse;
import com.drive.drive_manager.service.DriveParser.SyncResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * REST controller for Google Drive operations.
 */
@RestController
@RequestMapping("/api/drive")
public class DriveController {

    private static final Logger log = LoggerFactory.getLogger(DriveController.class);

    @Autowired
    private DriveParser driveParser;

    @Autowired
    private DriveCardRepository driveCardRepository;

    @Value("${r2.public-url}")
    private String r2PublicUrl;

    /**
     * Health check endpoint.
     * GET /api/drive/health
     */
    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("DriveController is healthy");
    }

    /**
     * Query drive_cards saved in MongoDB (with R2 URLs).
     * GET /api/drive/cards/db
     * GET /api/drive/cards/db?edition=E1
     * GET /api/drive/cards/db?edition=E1&color=B
     * GET /api/drive/cards/db?edition=E1&color=B&subEdition=1
     */
    @GetMapping("/cards/db")
    public ResponseEntity<List<Map<String, Object>>> getDbCards(
            @RequestParam(required = false) String edition,
            @RequestParam(required = false) String color,
            @RequestParam(required = false) String subEdition) {

        String baseUrl = r2PublicUrl.stripTrailing();

        List<Map<String, Object>> result = driveCardRepository.findAll().stream()
                .filter(c -> edition == null || edition.equalsIgnoreCase(c.getEdition()))
                .filter(c -> color == null || color.equalsIgnoreCase(c.getColorIdentity()))
                .filter(c -> subEdition == null || subEdition.equalsIgnoreCase(c.getSubEdition()))
                .map(c -> Map.<String, Object>of(
                        "id",             c.getId(),
                        "image_url",      baseUrl + "/cards/" + c.getId() + ".jpg",
                        "file_name",      c.getFileName() != null ? c.getFileName() : "",
                        "name",           c.getName() != null ? c.getName() : "",
                        "number",         c.getNumber() != null ? c.getNumber() : 0,
                        "color_identity", c.getColorIdentity() != null ? c.getColorIdentity() : "",
                        "edition",        c.getEdition() != null ? c.getEdition() : "",
                        "sub_edition",    c.getSubEdition() != null ? c.getSubEdition() : "",
                        "time_stamp",     c.getTimeStamp() != null ? c.getTimeStamp().toString() : ""
                ))
                .toList();

        return ResponseEntity.ok(result);
    }

    /**
     * Full drive listing without persistence.
     * GET /api/drive/cards?folderUrl=...
     *
     * POST /api/drive/cards
     * Body (optional): { "editions": ["ST1", "ST2", "E1"] }
     * Empty or omitted editions = return everything.
     */
    @GetMapping("/cards")
    public ResponseEntity<CardsResponse> readCards(
            @RequestParam(required = false) String folderUrl) {
        try {
            CardsResponse response = driveParser.listCards(folderUrl);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.error("GET /api/drive/cards bad request", e);
            return ResponseEntity.badRequest().build();
        } catch (IOException e) {
            log.error("GET /api/drive/cards failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/cards")
    public ResponseEntity<CardsResponse> readCardsFiltered(
            @RequestBody(required = false) CardsFilterRequest body) {
        List<String> editions    = body != null && body.editions()    != null ? body.editions()    : List.of();
        List<String> subEditions = body != null && body.subEditions() != null ? body.subEditions() : List.of();
        List<String> colors      = body != null && body.colors()      != null ? body.colors()      : List.of();
        try {
            CardsResponse response = driveParser.listCards(null, editions, subEditions, colors);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.error("POST /api/drive/cards bad request", e);
            return ResponseEntity.badRequest().build();
        } catch (IOException e) {
            log.error("POST /api/drive/cards failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    public record CardsFilterRequest(
            @JsonProperty("editions")    List<String> editions,
            @JsonProperty("subEditions") List<String> subEditions,
            @JsonProperty("colors")      List<String> colors) {}

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
            log.error("POST /api/drive/cards/map_to_db failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    public record SyncRequest(
            @JsonProperty("editions")    List<String> editions,
            @JsonProperty("subEditions") List<String> subEditions,
            @JsonProperty("colors")      List<String> colors
    ) {}
}
