package com.drive.drive_manager.controller;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.service.CardArtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A card's illustration (CardArtService), for whoever manages cards:
 * PUT    /api/cards/{id}/art — the art as the body (JPG, PNG or WebP, kept as it came): answers the card
 * GET    /api/cards/{id}/art — the art's file
 * DELETE /api/cards/{id}/art — the card loses its art: answers the card
 * PUT    /api/cards/{id}/render — the finished card drawn by the editor (a cache): answers the card
 * GET    /api/cards/{id}/render — that image
 */
@RestController
@RequestMapping("/api/cards")
@PreAuthorize("hasAuthority('manage_cards')")
public class CardArtController {

    private static final Logger log = LoggerFactory.getLogger(CardArtController.class);
    private final CardArtService art;

    public CardArtController(CardArtService art) {
        this.art = art;
    }

    @PutMapping(value = "/{id}/art", consumes = {"image/jpeg", "image/png", "image/webp", "application/octet-stream"})
    public ResponseEntity<Object> set(@PathVariable String id, @RequestBody byte[] image) {
        if (image == null || image.length == 0) return ResponseEntity.badRequest().body(Map.of("message", "Elige una imagen"));
        return apply(() -> art.set(id, image));
    }

    @GetMapping("/{id}/art")
    public ResponseEntity<byte[]> get(@PathVariable String id) {
        return art.get(id)
                .map(a -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(a.type()))
                        .cacheControl(CacheControl.noStore())
                        .body(a.bytes()))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping(value = "/{id}/render", consumes = {"image/jpeg", "image/png", "image/webp", "application/octet-stream"})
    public ResponseEntity<Object> setRender(@PathVariable String id, @RequestBody byte[] image) {
        if (image == null || image.length == 0) return ResponseEntity.badRequest().body(Map.of("message", "Falta la imagen"));
        return apply(() -> art.setRender(id, image));
    }

    @GetMapping("/{id}/render")
    public ResponseEntity<byte[]> render(@PathVariable String id) {
        // The key changes with every render: the address asks for it (?v=key), so it can be kept
        return art.getRender(id)
                .map(a -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(a.type().replace("jpg", "jpeg")))
                        .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePrivate())
                        .body(a.bytes()))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}/art")
    public ResponseEntity<Object> remove(@PathVariable String id) {
        return apply(() -> art.remove(id));
    }

    private static ResponseEntity<Object> apply(Supplier<Optional<Card>> change) {
        try {
            return change.get().<ResponseEntity<Object>>map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Could not change a card's art", e);
            return ResponseEntity.internalServerError().body(Map.of("message", "No se pudo guardar el arte"));
        }
    }
}
