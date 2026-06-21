package com.drive.drive_manager.simulator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/simulator")
public class RoomRestController {

    @Autowired
    private RoomService roomService;

    @PostMapping("/rooms")
    public ResponseEntity<Map<String, String>> createRoom(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }
        GameRoom room = roomService.createRoom();
        return ResponseEntity.ok(Map.of("roomId", room.getRoomId()));
    }

    @GetMapping("/rooms/{id}")
    public ResponseEntity<Map<String, Object>> getRoom(@PathVariable String id) {
        GameRoom room = roomService.getRoom(id);
        if (room == null) return ResponseEntity.notFound().build();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("roomId",      room.getRoomId());
        body.put("status",      room.getStatus().name());
        body.put("playerCount", room.getPlayers().size());
        body.put("playerIds",   new java.util.ArrayList<>(room.getPlayers().keySet()));
        return ResponseEntity.ok(body);
    }
}
