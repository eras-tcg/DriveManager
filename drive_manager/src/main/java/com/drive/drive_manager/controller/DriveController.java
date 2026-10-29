package com.drive.drive_manager.controller;

import com.drive.drive_manager.repository.DriveCardRepository;
import com.drive.drive_manager.service.DriveParser;
import com.drive.drive_manager.service.DriveParser.CardsResponse;
import com.drive.drive_manager.service.DriveParser.SyncResult;
import com.drive.drive_manager.service.R2Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import com.drive.drive_manager.dto.DriveCard;
import java.util.ArrayList;
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

    @Autowired
    private R2Service r2Service;

    @Value("${r2.public-url}")
    private String r2PublicUrl;

    @Autowired
    private MongoTemplate mongoTemplate;

    /**
     * Health check endpoint (public; used by the deploy). Pings MongoDB, so 200 means the data layer answers.
     * GET /api/drive/health
     */
    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        try {
            mongoTemplate.executeCommand("{ ping: 1 }");
            return ResponseEntity.ok("DriveController is healthy");
        } catch (RuntimeException e) {
            log.warn("Health check: MongoDB did not answer: {}", e.getMessage());
            return ResponseEntity.status(503).body("MongoDB unavailable");
        }
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
            @RequestParam(required = false) String subEdition,
            @RequestParam(required = false) String name) {

        String baseUrl = r2PublicUrl.stripTrailing();

        List<Map<String, Object>> result = driveCardRepository.findAll().stream()
                .filter(c -> edition == null || edition.equalsIgnoreCase(c.getEdition()))
                .filter(c -> color == null || color.equalsIgnoreCase(c.getColorIdentity()))
                .filter(c -> subEdition == null || subEdition.equalsIgnoreCase(c.getSubEdition()))
                .filter(c -> name == null || (c.getName() != null && c.getName().equalsIgnoreCase(name)))
                .map(c -> Map.<String, Object>of(
                        "id",             c.getId(),
                        "image_url",      c.imageUrl(baseUrl),
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
     * Rename a drive card stored in MongoDB.
     * PATCH /api/drive/cards/db/{id}/name
     * Body: { "name": "New Name" }
     */
    @PreAuthorize("hasAuthority('manage_cards')")
    @PatchMapping("/cards/db/{id}/name")
    public ResponseEntity<?> renameCard(@PathVariable String id, @RequestBody Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        return driveCardRepository.findById(id).map(card -> {
            card.setName(name.trim());
            driveCardRepository.save(card);
            return ResponseEntity.ok(Map.<String, Object>of("id", id, "name", card.getName()));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Delete a drive card's image from R2 and its DB record.
     * DELETE /api/drive/cards/db/{id}
     */
    /**
     * Gives the images uploaded before R2Service.CACHE_CONTROL existed that header, a page at a time (call again
     * with the next offset until it returns done).
     */
    @PreAuthorize("hasAuthority('manage_cards')")
    @PostMapping("/cards/cache-headers")
    public ResponseEntity<?> refreshCacheHeaders(@RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "100") int limit) {
        List<String> ids = driveCardRepository.findAll().stream().map(DriveCard::getId).sorted().toList();
        List<String> page = ids.subList(Math.min(offset, ids.size()), Math.min(offset + Math.max(1, limit), ids.size()));
        int updated = 0;
        List<String> failed = new ArrayList<>();
        for (String id : page) {
            try {
                r2Service.setCacheControl(id);
                updated++;
            } catch (Exception e) {
                failed.add(id);
            }
        }
        int next = offset + page.size();
        return ResponseEntity.ok(Map.of("updated", updated, "failed", failed, "next", next, "total", ids.size(), "done", next >= ids.size()));
    }

    @PreAuthorize("hasAuthority('manage_cards')")
    @DeleteMapping("/cards/db/{id}")
    public ResponseEntity<?> deleteCard(@PathVariable String id) {
        if (!driveCardRepository.existsById(id))
            return ResponseEntity.notFound().build();
        r2Service.delete(id);
        driveCardRepository.deleteById(id);
        log.info("Deleted drive card and R2 image: {}", id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Proxies a card image from R2 through the API so the browser can load it
     * without CORS restrictions (e.g. for canvas/PDF generation).
     * GET /api/drive/cards/db/{id}/image
     */
    @GetMapping("/cards/db/{id}/image")
    public ResponseEntity<byte[]> proxyImage(@PathVariable String id) {
        if (!driveCardRepository.existsById(id))
            return ResponseEntity.notFound().build();
        try {
            byte[] data = r2Service.getImage(id);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(data);
        } catch (Exception e) {
            log.error("Failed to proxy image for card {}", id, e);
            return ResponseEntity.notFound().build();
        }
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

    @PreAuthorize("hasAuthority('manage_cards')")
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

    @PreAuthorize("hasAuthority('manage_cards')")
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
