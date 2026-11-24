package com.drive.drive_manager.controller;

import com.drive.drive_manager.repository.DriveCardRepository;
import com.drive.drive_manager.service.R2Service;
import com.drive.drive_manager.service.TestCards;
import org.springframework.security.core.Authentication;
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


import com.drive.drive_manager.dto.DriveCard;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The card images (records in drive_cards, files in R2): listing, renaming, deleting and the image proxy.
 */
@RestController
@RequestMapping("/api/drive")
public class DriveController {

    private static final Logger log = LoggerFactory.getLogger(DriveController.class);

    @Autowired
    private DriveCardRepository driveCardRepository;

    @Autowired
    private R2Service r2Service;

    /** Editions an admin hid: their images only for admins and testers. */
    @Autowired(required = false)
    private com.drive.drive_manager.service.HiddenEditions hiddenEditions = com.drive.drive_manager.service.HiddenEditions.none();

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
            @RequestParam(required = false) String name,
            Authentication auth) {

        String baseUrl = r2PublicUrl.stripTrailing();
        boolean testCards = TestCards.canSee(auth);

        List<Map<String, Object>> result = hiddenEditions.visible(driveCardRepository.findAll(),
                        com.drive.drive_manager.dto.DriveCard::getEdition, com.drive.drive_manager.dto.DriveCard::getSubEdition, auth).stream()
                .filter(c -> testCards || !TestCards.isTestEdition(c.getEdition()))
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
        DriveCard card = driveCardRepository.findById(id).orElse(null);
        if (card == null)
            return ResponseEntity.notFound().build();
        // A card made in the editor has no image of its own in R2 (its render lives with the card). A Drive image that
        // cannot be removed from R2 (no R2 locally) stays there unused: the record goes anyway
        if (!card.fromWeb()) {
            try {
                r2Service.delete(id);
            } catch (RuntimeException e) {
                log.warn("Could not delete the R2 image of {}: {}", id, e.getMessage());
            }
        }
        driveCardRepository.deleteById(id);
        log.info("Deleted drive card {}", id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Proxies a card image from R2 through the API so the browser can load it
     * without CORS restrictions (e.g. for canvas/PDF generation).
     * GET /api/drive/cards/db/{id}/image
     */
    @GetMapping("/cards/db/{id}/image")
    public ResponseEntity<byte[]> proxyImage(@PathVariable String id, Authentication auth) {
        DriveCard card = driveCardRepository.findById(id).orElse(null);
        if (card == null || TestCards.hidden(card.getEdition(), auth) || hiddenEditions.hidden(card.getEdition(), card.getSubEdition(), auth))
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
}
