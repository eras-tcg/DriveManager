package com.drive.drive_manager.controller;

import com.drive.drive_manager.service.AvatarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * The signed-in player's icon. The image is the request body (this service does not take multipart uploads); it
 * is cut to a square and scaled before it is stored.
 */
@RestController
@RequestMapping("/api/drive/avatars")
public class AvatarController {

    private static final Logger log = LoggerFactory.getLogger(AvatarController.class);
    private final AvatarService avatars;

    public AvatarController(AvatarService avatars) {
        this.avatars = avatars;
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> mine(Authentication auth) {
        return ResponseEntity.ok(body(avatars.urlOf(auth.getName())));
    }

    @PutMapping(value = "/me", consumes = {"image/jpeg", "image/png", "image/gif", "image/bmp", "application/octet-stream"})
    public ResponseEntity<Map<String, Object>> upload(Authentication auth, @RequestBody byte[] image) {
        if (image == null || image.length == 0) return ResponseEntity.badRequest().body(Map.of("message", "Elige una imagen"));
        if (image.length > AvatarService.MAX_BYTES) {
            return ResponseEntity.badRequest().body(Map.of("message", "La imagen pesa más de 5 MB"));
        }
        try {
            return ResponseEntity.ok(body(avatars.put(auth.getName(), image)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Could not store the icon of {}", auth.getName(), e);
            return ResponseEntity.internalServerError().body(Map.of("message", "No se pudo guardar el ícono"));
        }
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> remove(Authentication auth) {
        avatars.delete(auth.getName());
        return ResponseEntity.noContent().build();
    }

    private static Map<String, Object> body(String url) {
        Map<String, Object> out = new HashMap<>();
        out.put("url", url);
        return out;
    }
}
