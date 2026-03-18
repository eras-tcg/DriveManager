package com.drive.drive_manager.controller;

import com.drive.drive_manager.service.DriveWatchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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

    @Value("${drive.webhook-token}")
    private String webhookToken;

    private final DriveWatchService driveWatchService;

    public DriveWebhookController(DriveWatchService driveWatchService) {
        this.driveWatchService = driveWatchService;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> onDriveChange(
            @RequestHeader("X-Goog-Channel-ID") String channelId,
            @RequestHeader("X-Goog-Resource-State") String resourceState,
            @RequestHeader(value = "X-Goog-Channel-Token", required = false) String token) {

        if (!webhookToken.equals(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Google sends a one-time "sync" message right after channel registration.
        if ("sync".equals(resourceState)) {
            return ResponseEntity.ok().build();
        }

        try {
            driveWatchService.processChanges(channelId);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        return ResponseEntity.ok().build();
    }
}
