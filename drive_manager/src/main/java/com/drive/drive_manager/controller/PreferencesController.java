package com.drive.drive_manager.controller;

import com.drive.drive_manager.service.PreferencesService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The signed-in player's preferences for the site and the table, kept with their account ({@link PreferencesService}):
 * read when they sign in, saved whole each time one changes.
 */
@RestController
@RequestMapping("/api/drive/preferences")
public class PreferencesController {

    private final PreferencesService preferences;

    public PreferencesController(PreferencesService preferences) {
        this.preferences = preferences;
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> mine(Authentication auth) {
        return ResponseEntity.ok(Map.of("prefs", preferences.of(auth.getName())));
    }

    @PutMapping("/me")
    public ResponseEntity<Map<String, Object>> save(Authentication auth, @RequestBody Map<String, Object> body) {
        Object prefs = body == null ? null : body.get("prefs");
        if (!(prefs instanceof Map<?, ?>)) return ResponseEntity.badRequest().body(Map.of("message", "Faltan las preferencias"));
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> saved = preferences.put(auth.getName(), (Map<String, Object>) prefs);
            return ResponseEntity.ok(Map.of("prefs", saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
