package com.drive.drive_manager.controller;

import com.drive.drive_manager.service.ActivityTracker;
import com.drive.drive_manager.simulator.room.RoomService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * For a server that sleeps until someone visits the site:
 * GET /api/system/activity → how long nobody has used it and the simulator rooms with players (the idle check, open)
 */
@RestController
public class SystemController {

    private final ActivityTracker activityTracker;
    private final RoomService roomService;

    public SystemController(ActivityTracker activityTracker, RoomService roomService) {
        this.activityTracker = activityTracker;
        this.roomService = roomService;
    }

    @GetMapping("/api/system/activity")
    public Map<String, Object> activity() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idleSeconds", activityTracker.idleSeconds());
        body.put("activeRooms", roomService.activeRooms());
        body.put("startedAt", activityTracker.startedAt().toString());
        return body;
    }
}
