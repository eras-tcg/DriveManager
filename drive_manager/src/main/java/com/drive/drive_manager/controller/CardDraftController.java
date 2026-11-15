package com.drive.drive_manager.controller;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.CardDraft;
import com.drive.drive_manager.service.CardDraftService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The card editor's drafts (CardDraftService), for whoever manages cards:
 * GET    /api/card-drafts                 — every draft
 * GET    /api/card-drafts/{id}            — one draft
 * GET    /api/card-drafts/by-card/{metaId} — the draft of a published card (404 if it has none)
 * POST   /api/card-drafts                 — {"metaId": null|"…", "meta": {…}, "design": {…}}: a new draft
 * PUT    /api/card-drafts/{id}            — {"meta": {…}, "design": {…}}: saves it
 * DELETE /api/card-drafts/{id}            — discards it with its art
 * PUT    /api/card-drafts/{id}/art        — the art as the body (JPG, PNG or WebP, kept as it came)
 * GET    /api/card-drafts/{id}/art        — the art's file
 * DELETE /api/card-drafts/{id}/art        — the draft loses its art
 * A code used by another card or draft answers 409, missing or refused data 400.
 */
@RestController
@RequestMapping("/api/card-drafts")
@PreAuthorize("hasAuthority('manage_cards')")
public class CardDraftController {

    private static final Logger log = LoggerFactory.getLogger(CardDraftController.class);
    private final CardDraftService drafts;

    public CardDraftController(CardDraftService drafts) {
        this.drafts = drafts;
    }

    public record DraftRequest(String metaId, Card meta, Map<String, Object> design) {
    }

    @GetMapping
    public List<CardDraft> list() {
        return drafts.list();
    }

    @GetMapping("/{id}")
    public ResponseEntity<CardDraft> get(@PathVariable String id) {
        return ResponseEntity.of(drafts.get(id));
    }

    @GetMapping("/by-card/{metaId}")
    public ResponseEntity<CardDraft> forCard(@PathVariable String metaId) {
        return ResponseEntity.of(drafts.forCard(metaId));
    }

    @PostMapping
    public ResponseEntity<Object> create(Authentication auth, @RequestBody DraftRequest request) {
        return apply(() -> Optional.of(drafts.create(request.metaId(), request.meta(), request.design(), auth.getName())));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Object> update(Authentication auth, @PathVariable String id, @RequestBody DraftRequest request) {
        return apply(() -> drafts.update(id, request.meta(), request.design(), auth.getName()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        return drafts.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PutMapping(value = "/{id}/art", consumes = {"image/jpeg", "image/png", "image/webp", "application/octet-stream"})
    public ResponseEntity<Object> setArt(Authentication auth, @PathVariable String id, @RequestBody byte[] image) {
        if (image == null || image.length == 0) return ResponseEntity.badRequest().body(Map.of("message", "Elige una imagen"));
        return apply(() -> drafts.setArt(id, image, auth.getName()));
    }

    @GetMapping("/{id}/art")
    public ResponseEntity<byte[]> art(@PathVariable String id) {
        return drafts.art(id)
                .map(a -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(a.type()))
                        .cacheControl(CacheControl.noStore())
                        .body(a.bytes()))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}/art")
    public ResponseEntity<Object> removeArt(Authentication auth, @PathVariable String id) {
        return apply(() -> drafts.removeArt(id, auth.getName()));
    }

    private static ResponseEntity<Object> apply(Supplier<Optional<CardDraft>> change) {
        try {
            return change.get().<ResponseEntity<Object>>map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
        } catch (CardDraftService.CodeTaken e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Could not change a card draft", e);
            return ResponseEntity.internalServerError().body(Map.of("message", "No se pudo guardar el borrador"));
        }
    }
}
