package com.drive.drive_manager.controller;

import com.drive.drive_manager.service.MissionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The signed-in player's daily and weekly missions: reading them (which counts the day's login) and claiming a
 * completed one's gems. A refused claim answers 400 with a message.
 */
@RestController
@RequestMapping("/api/drive/missions")
public class MissionController {

    private final MissionService missions;

    public MissionController(MissionService missions) {
        this.missions = missions;
    }

    @GetMapping("/me")
    public ResponseEntity<MissionService.Board> mine(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(missions.open(auth.getName()));
    }

    /** `period`: "daily" or "weekly". */
    public record ClaimRequest(String period, String missionId) {
    }

    @PostMapping("/me/claim")
    public ResponseEntity<?> claim(Authentication auth, @RequestBody ClaimRequest request) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(401).build();
        try {
            return ResponseEntity.ok(missions.claim(auth.getName(), "weekly".equals(request.period()), request.missionId()));
        } catch (MissionService.MissionException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
