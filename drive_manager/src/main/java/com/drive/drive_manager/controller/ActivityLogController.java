package com.drive.drive_manager.controller;

import com.drive.drive_manager.service.ActivityLogService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * GET /api/drive/activity — the players' activity log for the administrators (manage_users), newest first:
 * ?actions=A,B (any of them), user (part of a username), from / to (ISO instants, inclusive), page, size.
 */
@RestController
@RequestMapping("/api/drive/activity")
public class ActivityLogController {

    private final ActivityLogService log;

    public ActivityLogController(ActivityLogService log) {
        this.log = log;
    }

    @PreAuthorize("hasAuthority('manage_users')")
    @GetMapping
    public ResponseEntity<Object> search(@RequestParam(required = false) List<String> actions,
                                         @RequestParam(required = false) String user,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "50") int size) {
        try {
            return ResponseEntity.ok(log.search(actions, user, instant(from), instant(to), page, size));
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Fecha no válida"));
        }
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
