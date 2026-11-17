package com.drive.drive_manager.controller;

import com.drive.drive_manager.dto.EditionMetadata;
import com.drive.drive_manager.repository.EditionMetadataRepository;
import com.drive.drive_manager.service.DriveClientFactory;
import com.drive.drive_manager.service.EditionImageService;
import com.drive.drive_manager.service.R2Service;
import com.drive.drive_manager.service.TestCards;
import org.springframework.security.core.Authentication;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;

/**
 * CRUD for edition metadata + optional image pull to R2.
 *
 * GET    /api/drive/editions              — list all
 * GET    /api/drive/editions/{editionId}  — get one
 * POST   /api/drive/editions              — create/update
 * DELETE /api/drive/editions/{editionId}  — delete
 * POST   /api/drive/editions/{editionId}/pull-image  — download banner from Drive → R2
 * PUT    /api/drive/editions/{editionId}/image       — the cover chosen on the site (the image is the body)
 * GET    /api/drive/editions/image/{file}            — a cover kept in the local folder (local development)
 */
@RestController
@RequestMapping("/api/drive/editions")
public class EditionMetadataController {

    private static final Logger log = LoggerFactory.getLogger(EditionMetadataController.class);

    private final EditionMetadataRepository repo;
    private final DriveClientFactory driveClientFactory;
    private final R2Service r2Service;
    private final EditionImageService covers;

    public EditionMetadataController(EditionMetadataRepository repo,
                                     DriveClientFactory driveClientFactory,
                                     R2Service r2Service,
                                     EditionImageService covers) {
        this.repo = repo;
        this.driveClientFactory = driveClientFactory;
        this.r2Service = r2Service;
        this.covers = covers;
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
        return ResponseEntity.ok(repo.findAll().stream().filter(e -> testCards || !TestCards.isTestEdition(e.getEditionId())).toList());
    }

    @GetMapping("/{editionId}")
    public ResponseEntity<EditionMetadata> getOne(@PathVariable String editionId, Authentication auth) {
        return repo.findById(editionId)
                .filter(e -> !TestCards.hidden(e.getEditionId(), auth))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('manage_editions')")
    @PostMapping
    public ResponseEntity<Object> upsert(@RequestBody EditionMetadata body) {
        // If editionImage is set and looks like a Drive file ID (not a URL), pull it to R2
        if (body.getEditionImage() != null
                && !body.getEditionImage().isBlank()
                && !body.getEditionImage().startsWith("http")) {
            try {
                String driveFileId = body.getEditionImage().trim();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                driveClientFactory.create()
                        .files()
                        .get(driveFileId)
                        .executeMediaAndDownloadTo(baos);
                byte[] bytes = baos.toByteArray();
                String r2Key = "editions/" + body.getEditionId() + ".jpg";
                String url = r2Service.uploadRaw(r2Key, new ByteArrayInputStream(bytes), bytes.length);
                body.setEditionImage(url);
                log.info("Auto-pulled edition image for {} to R2: {}", body.getEditionId(), url);
            } catch (GeneralSecurityException | IOException e) {
                log.error("Failed to pull image for edition {} during upsert", body.getEditionId(), e);
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", "Saved edition but failed to pull image: " + e.getMessage()));
            }
        }
        // If editionImage is empty on an update, preserve the existing image
        if (body.getEditionImage() == null || body.getEditionImage().isBlank()) {
            repo.findById(body.getEditionId())
                    .ifPresent(existing -> body.setEditionImage(existing.getEditionImage()));
        }
        repo.findById(body.getEditionId()).ifPresent(existing -> keepSlots(body, existing));
        return ResponseEntity.ok(repo.save(body));
    }

    /** A save that does not send the slots or the cover's framing (the shop's form) keeps the ones the edition has. */
    static void keepSlots(EditionMetadata body, EditionMetadata existing) {
        if (body.getColors() == null) body.setColors(existing.getColors());
        if (body.getCardsPerColor() == null) body.setCardsPerColor(existing.getCardsPerColor());
        if (body.getCardsByColor() == null) body.setCardsByColor(existing.getCardsByColor());
        if (body.getCoverFrame() == null) body.setCoverFrame(existing.getCoverFrame());
    }

    @PreAuthorize("hasAuthority('manage_editions')")
    @DeleteMapping("/{editionId}")
    public ResponseEntity<Void> delete(@PathVariable String editionId) {
        repo.deleteById(editionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Downloads the edition banner from Google Drive and stores it in R2
     * under editions/{editionId}.jpg, then updates editionImage to the R2 URL.
     *
     * POST /api/drive/editions/{editionId}/pull-image
     * Body: { "driveFileId": "1abc...xyz" }
     */
    @PreAuthorize("hasAuthority('manage_editions')")
    @PostMapping("/{editionId}/pull-image")
    public ResponseEntity<Object> pullImage(@PathVariable String editionId,
                                            @RequestBody PullImageRequest body) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            driveClientFactory.create()
                    .files()
                    .get(body.driveFileId())
                    .executeMediaAndDownloadTo(baos);
            byte[] bytes = baos.toByteArray();

            String r2Key = "editions/" + editionId + ".jpg";
            String url = r2Service.uploadRaw(r2Key, new ByteArrayInputStream(bytes), bytes.length);

            EditionMetadata edition = repo.findById(editionId).orElseGet(() -> {
                EditionMetadata e = new EditionMetadata();
                e.setEditionId(editionId);
                return e;
            });
            edition.setEditionImage(url);
            repo.save(edition);

            log.info("Pulled edition image for {} to R2: {}", editionId, url);
            return ResponseEntity.ok(Map.of("imageUrl", url));

        } catch (GeneralSecurityException | IOException e) {
            log.error("Failed to pull image for edition {}", editionId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    public record PullImageRequest(@JsonProperty("driveFileId") String driveFileId) {}
}
