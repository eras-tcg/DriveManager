package com.drive.drive_manager.controller;

import com.drive.drive_manager.service.ProgressionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * The signed-in player's progression missions: reading them (which counts the day's login), claiming a welcome step
 * or a milestone's next tier, and noting a visit to a page that counts. A refused claim answers 400 with a message.
 */
@RestController
@RequestMapping("/api/drive/progression")
public class ProgressionController {

    private final ProgressionService progression;

    public ProgressionController(ProgressionService progression) {
        this.progression = progression;
    }

    @GetMapping("/me")
    public ResponseEntity<ProgressionService.Board> mine(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(progression.open(auth.getName()));
    }

    /** `kind`: "welcome" or "milestone"; `editionId`: the structure deck chosen, for the structure step. */
    public record ClaimRequest(String kind, String id, String editionId) {
    }

    /** {board, opening}: opening is the structure deck given, when the claim gave one. */
    @PostMapping("/me/claim")
    public ResponseEntity<?> claim(Authentication auth, @RequestBody ClaimRequest request) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(401).build();
        try {
            Map<String, Object> body = new HashMap<>();
            if ("milestone".equals(request.kind())) {
                body.put("board", progression.claimMilestone(auth.getName(), request.id()));
            } else {
                ProgressionService.Claimed claimed = progression.claimWelcome(auth.getName(), request.id(), request.editionId());
                body.put("board", claimed.board());
                if (claimed.opening() != null) body.put("opening", claimed.opening());
            }
            return ResponseEntity.ok(body);
        } catch (ProgressionService.ProgressionException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    public record VisitRequest(String page) {
    }

    @PostMapping("/me/visit")
    public ResponseEntity<Void> visit(Authentication auth, @RequestBody VisitRequest request) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(401).build();
        progression.visit(auth.getName(), request.page());
        return ResponseEntity.noContent().build();
    }
}
