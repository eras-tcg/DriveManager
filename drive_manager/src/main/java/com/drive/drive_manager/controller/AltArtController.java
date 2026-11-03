package com.drive.drive_manager.controller;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.service.AltArtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * A card's alternative art, set from the card's detail by whoever manages cards (AltArtService):
 * PUT    /api/cards/{id}/alt-image — the image as the body (JPG, PNG, GIF or BMP); answers the card
 * DELETE /api/cards/{id}/alt-image — the card has no alternative art any more; answers the card
 */
@RestController
@RequestMapping("/api/cards")
public class AltArtController {

    private static final Logger log = LoggerFactory.getLogger(AltArtController.class);
    private final AltArtService altArt;

    public AltArtController(AltArtService altArt) {
        this.altArt = altArt;
    }

    @PreAuthorize("hasAuthority('manage_cards')")
    @PutMapping(value = "/{id}/alt-image", consumes = {"image/jpeg", "image/png", "image/gif", "image/bmp", "application/octet-stream"})
    public ResponseEntity<Object> upload(@PathVariable String id, @RequestBody byte[] image) {
        if (image == null || image.length == 0) return ResponseEntity.badRequest().body(Map.of("message", "Elige una imagen"));
        if (image.length > AltArtService.MAX_BYTES) return ResponseEntity.badRequest().body(Map.of("message", "La imagen pesa más de 8 MB"));
        try {
            return altArt.put(id, image).<ResponseEntity<Object>>map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Could not store the alternative art of card {}", id, e);
            return ResponseEntity.internalServerError().body(Map.of("message", "No se pudo guardar el arte alternativo"));
        }
    }

    @PreAuthorize("hasAuthority('manage_cards')")
    @DeleteMapping("/{id}/alt-image")
    public ResponseEntity<Card> remove(@PathVariable String id) {
        return altArt.remove(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
