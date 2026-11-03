package com.drive.drive_manager.controller;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.service.AltArtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * A card's alternative arts, set from the card's detail by whoever manages cards (AltArtService); each answers the card:
 * POST   /api/cards/{id}/alt-arts[?odds=0.001]   — a new art, the image as the body (JPG, PNG, GIF or BMP)
 * PUT    /api/cards/{id}/alt-arts/{artId}        — a new image for that art
 * PATCH  /api/cards/{id}/alt-arts/{artId}        — {"odds": 0.001} its own odds, or {"odds": null} for the shop's
 * DELETE /api/cards/{id}/alt-arts/{artId}        — the card loses that art
 */
@RestController
@RequestMapping("/api/cards")
public class AltArtController {

    private static final Logger log = LoggerFactory.getLogger(AltArtController.class);
    private final AltArtService altArt;

    public AltArtController(AltArtService altArt) {
        this.altArt = altArt;
    }

    /** An edit of a card's arts: a refused image or value answers 400, anything else 500. */
    private interface Change {
        Optional<Card> run() throws Exception;
    }

    @PreAuthorize("hasAuthority('manage_cards')")
    @PostMapping(value = "/{id}/alt-arts", consumes = {"image/jpeg", "image/png", "image/gif", "image/bmp", "application/octet-stream"})
    public ResponseEntity<Object> add(@PathVariable String id, @RequestParam(required = false) Double odds, @RequestBody byte[] image) {
        ResponseEntity<Object> refused = checkImage(image);
        return refused != null ? refused : apply(id, () -> altArt.add(id, image, odds));
    }

    @PreAuthorize("hasAuthority('manage_cards')")
    @PutMapping(value = "/{id}/alt-arts/{artId}", consumes = {"image/jpeg", "image/png", "image/gif", "image/bmp", "application/octet-stream"})
    public ResponseEntity<Object> replace(@PathVariable String id, @PathVariable String artId, @RequestBody byte[] image) {
        ResponseEntity<Object> refused = checkImage(image);
        return refused != null ? refused : apply(id, () -> altArt.replace(id, artId, image));
    }

    public record OddsRequest(Double odds) {
    }

    @PreAuthorize("hasAuthority('manage_cards')")
    @PatchMapping("/{id}/alt-arts/{artId}")
    public ResponseEntity<Object> setOdds(@PathVariable String id, @PathVariable String artId, @RequestBody OddsRequest request) {
        return apply(id, () -> altArt.setOdds(id, artId, request == null ? null : request.odds()));
    }

    @PreAuthorize("hasAuthority('manage_cards')")
    @DeleteMapping("/{id}/alt-arts/{artId}")
    public ResponseEntity<Object> remove(@PathVariable String id, @PathVariable String artId) {
        return apply(id, () -> altArt.remove(id, artId));
    }

    private static ResponseEntity<Object> checkImage(byte[] image) {
        if (image == null || image.length == 0) return ResponseEntity.badRequest().body(Map.of("message", "Elige una imagen"));
        if (image.length > AltArtService.MAX_BYTES) return ResponseEntity.badRequest().body(Map.of("message", "La imagen pesa más de 8 MB"));
        return null;
    }

    private static ResponseEntity<Object> apply(String id, Change change) {
        try {
            return change.run().<ResponseEntity<Object>>map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Could not change the alternative arts of card {}", id, e);
            return ResponseEntity.internalServerError().body(Map.of("message", "No se pudo guardar el arte alternativo"));
        }
    }
}
