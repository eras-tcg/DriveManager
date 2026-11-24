package com.drive.drive_manager.controller;

import com.drive.drive_manager.dto.EditionMetadata;
import com.drive.drive_manager.repository.EditionMetadataRepository;
import com.drive.drive_manager.service.EditionImageService;
import com.drive.drive_manager.service.HiddenEditions;
import com.drive.drive_manager.service.TestCards;
import org.springframework.security.core.Authentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * CRUD for edition metadata and its cover.
 *
 * GET    /api/drive/editions              — list all
 * GET    /api/drive/editions/{editionId}  — get one
 * POST   /api/drive/editions              — create/update
 * DELETE /api/drive/editions/{editionId}  — delete
 * PUT    /api/drive/editions/{editionId}/image       — the cover chosen on the site (the image is the body)
 * GET    /api/drive/editions/image/{file}            — a cover kept in the local folder (local development)
 * PUT    /api/drive/editions/{editionId}/hidden      — hides it (in development) or shows it: {hidden: true|false}
 *
 * A hidden edition is listed only to admins and testers ({@link HiddenEditions}).
 */
@RestController
@RequestMapping("/api/drive/editions")
public class EditionMetadataController {

    private static final Logger log = LoggerFactory.getLogger(EditionMetadataController.class);

    private final EditionMetadataRepository repo;
    private final EditionImageService covers;
    private final HiddenEditions hiddenEditions;

    public EditionMetadataController(EditionMetadataRepository repo,
                                     EditionImageService covers,
                                     HiddenEditions hiddenEditions) {
        this.repo = repo;
        this.covers = covers;
        this.hiddenEditions = hiddenEditions;
    }

    public record HiddenRequest(Boolean hidden) {
    }

    @PreAuthorize("hasAuthority('manage_editions')")
    @PutMapping("/{editionId}/hidden")
    public ResponseEntity<EditionMetadata> setHidden(@PathVariable String editionId, @RequestBody HiddenRequest body) {
        if (body == null || body.hidden() == null) return ResponseEntity.badRequest().build();
        return repo.findById(editionId).map(edition -> {
            edition.setHidden(body.hidden());
            EditionMetadata saved = repo.save(edition);
            hiddenEditions.refresh();
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('manage_editions')")
    @PutMapping(value = "/{editionId}/image", consumes = {"image/jpeg", "image/png", "image/webp", "application/octet-stream"})
    public ResponseEntity<Object> setImage(@PathVariable String editionId, @RequestBody byte[] image) {
        if (image == null || image.length == 0) return ResponseEntity.badRequest().body(Map.of("message", "Elige una imagen"));
        try {
            return covers.set(editionId, image).<ResponseEntity<Object>>map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Could not store the cover of edition {}", editionId, e);
            return ResponseEntity.internalServerError().body(Map.of("message", "No se pudo guardar la portada"));
        }
    }

    @GetMapping("/image/{file}")
    public ResponseEntity<byte[]> image(@PathVariable String file) {
        // Each cover has its own name: it can be kept for good
        return covers.get(file)
                .map(bytes -> ResponseEntity.ok()
                        .contentType(org.springframework.http.MediaType.parseMediaType(
                                "image/" + file.substring(file.lastIndexOf('.') + 1)))
                        .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofDays(365)))
                        .body(bytes))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<EditionMetadata>> listAll(Authentication auth) {
        boolean testCards = TestCards.canSee(auth);
        boolean hidden = HiddenEditions.canSee(auth);
        return ResponseEntity.ok(repo.findAll().stream()
                .filter(e -> testCards || !TestCards.isTestEdition(e.getEditionId()))
                .filter(e -> hidden || !e.isHiddenEdition())
                .toList());
    }

    @GetMapping("/{editionId}")
    public ResponseEntity<EditionMetadata> getOne(@PathVariable String editionId, Authentication auth) {
        return repo.findById(editionId)
                .filter(e -> !TestCards.hidden(e.getEditionId(), auth))
                .filter(e -> !e.isHiddenEdition() || HiddenEditions.canSee(auth))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('manage_editions')")
    @PostMapping
    public ResponseEntity<EditionMetadata> upsert(@RequestBody EditionMetadata body) {
        // If editionImage is empty on an update, preserve the existing image
        if (body.getEditionImage() == null || body.getEditionImage().isBlank()) {
            repo.findById(body.getEditionId())
                    .ifPresent(existing -> body.setEditionImage(existing.getEditionImage()));
        }
        repo.findById(body.getEditionId()).ifPresent(existing -> keepSlots(body, existing));
        EditionMetadata saved = repo.save(body);
        hiddenEditions.refresh();
        return ResponseEntity.ok(saved);
    }

    /** A save that does not send the slots, the cover's framing or the hidden mark (the shop's form) keeps the ones the edition has. */
    static void keepSlots(EditionMetadata body, EditionMetadata existing) {
        if (body.getColors() == null) body.setColors(existing.getColors());
        if (body.getCardsPerColor() == null) body.setCardsPerColor(existing.getCardsPerColor());
        if (body.getCardsByColor() == null) body.setCardsByColor(existing.getCardsByColor());
        if (body.getRegulation() == null) body.setRegulation(existing.getRegulation());
        if (body.getCoverFrame() == null) body.setCoverFrame(existing.getCoverFrame());
        if (body.getHidden() == null) body.setHidden(existing.getHidden());
    }

    @PreAuthorize("hasAuthority('manage_editions')")
    @DeleteMapping("/{editionId}")
    public ResponseEntity<Void> delete(@PathVariable String editionId) {
        repo.deleteById(editionId);
        hiddenEditions.refresh();
        return ResponseEntity.noContent().build();
    }
}
