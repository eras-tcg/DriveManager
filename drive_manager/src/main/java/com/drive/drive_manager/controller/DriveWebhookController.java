package com.drive.drive_manager.controller;

import com.drive.drive_manager.service.DriveWatchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives Google Drive push notifications.
 *
 * The URL configured in drive.webhook-url must point here and be reachable
 * over public HTTPS. Google will POST to it whenever any file in the watched
 * Drive changes.
 *
 * POST /api/drive/webhook
 */
@RestController
@RequestMapping("/api/drive")
public class DriveWebhookController {

    private final DriveWatchService driveWatchService;

    public DriveWebhookController(DriveWatchService driveWatchService) {
        this.driveWatchService = driveWatchService;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> onDriveChange(
            @RequestHeader("X-Goog-Channel-ID") String channelId,
            @RequestHeader("X-Goog-Resource-State") String resourceState) {

        // Google sends a one-time "sync" message right after channel registration.
        // It carries no change data — just acknowledge it.
        if ("sync".equals(resourceState)) {
            return ResponseEntity.ok().build();
        }

        driveWatchService.processChanges(channelId);
        return ResponseEntity.ok().build();
    }
}
