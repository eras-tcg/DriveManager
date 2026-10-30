package com.drive.drive_manager.controller;

import com.drive.drive_manager.dto.SyncReport;
import com.drive.drive_manager.service.ActivityTracker;
import com.drive.drive_manager.service.DriveCatchUpService;
import com.drive.drive_manager.simulator.RoomService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * For a server that sleeps until someone visits the site:
 * GET  /api/system/activity  → how long nobody has used it and the simulator rooms with players (the idle check, open)
 * GET  /api/drive/sync/report → the last check of Drive for changes (admins)
 * POST /api/drive/sync/run    → checks Drive again now (admins)
 */
@RestController
public class SystemController {

    private final ActivityTracker activityTracker;
    private final RoomService roomService;
    private final DriveCatchUpService driveCatchUpService;

    public SystemController(ActivityTracker activityTracker, RoomService roomService, DriveCatchUpService driveCatchUpService) {
        this.activityTracker = activityTracker;
        this.roomService = roomService;
        this.driveCatchUpService = driveCatchUpService;
    }

    @GetMapping("/api/system/activity")
    public Map<String, Object> activity() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idleSeconds", activityTracker.idleSeconds());
        body.put("activeRooms", roomService.activeRooms());
        body.put("startedAt", activityTracker.startedAt().toString());
        return body;
    }

    @PreAuthorize("hasAuthority('manage_cards')")
    @GetMapping("/api/drive/sync/report")
    public ResponseEntity<SyncReport> report() {
        SyncReport report = driveCatchUpService.report();
        return report == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(report);
    }

    @PreAuthorize("hasAuthority('manage_cards')")
    @PostMapping("/api/drive/sync/run")
    public ResponseEntity<SyncReport> run() {
        return ResponseEntity.accepted().body(driveCatchUpService.start());
    }
}
