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

    /** Permissions of the site's edit mode: they allow test rooms. */
    private static final java.util.Set<String> EDIT_PERMISSIONS = java.util.Set.of("manage_home", "manage_editions", "manage_cards");

    /** A room owned by its creator; {@code editor}: a test room, played alone on both sides (edit mode only). */
    @PostMapping("/rooms")
    public ResponseEntity<Map<String, String>> createRoom(Authentication auth, @RequestParam(defaultValue = "false") boolean editor) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }
        if (editor && auth.getAuthorities().stream().noneMatch(a -> EDIT_PERMISSIONS.contains(a.getAuthority()))) {
            return ResponseEntity.status(403).build();
        }
        GameRoom room = roomService.createRoom(auth.getName(), editor);
        return ResponseEntity.ok(Map.of("roomId", room.getRoomId()));
    }

    /** The owner, waiting alone, plays both sides: the room becomes a test room (edit mode only). */
    @PostMapping("/rooms/{id}/solo")
    public ResponseEntity<Map<String, String>> playAlone(@PathVariable String id, Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(401).build();
        if (auth.getAuthorities().stream().noneMatch(a -> EDIT_PERMISSIONS.contains(a.getAuthority()))) return ResponseEntity.status(403).build();
        String error = roomService.makeTestRoom(id, auth.getName());
        return error == null ? ResponseEntity.ok(Map.of("roomId", id)) : ResponseEntity.badRequest().body(Map.of("message", error));
    }

    /** Open rooms to enter (public): who is in each and how it is going. */
    @GetMapping("/rooms")
    public ResponseEntity<java.util.List<Map<String, Object>>> listRooms(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(roomService.openRooms().stream().map(this::summary).toList());
    }

    private Map<String, Object> summary(GameRoom room) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("roomId",      room.getRoomId());
        body.put("status",      room.getStatus().name());
        body.put("playerCount", room.getPlayers().size());
        body.put("playerIds",   new java.util.ArrayList<>(room.getPlayers().keySet()));
        body.put("players",     room.getPlayers().values().stream().map(PlayerState::getUsername).toList());
        body.put("createdAt",   room.getCreatedAt().toString());
        return body;
    }

    /** Cards the engine does not run in full (metadata id → what is missing), for the card catalog's warning. */
    @GetMapping("/unprogrammed")
    public ResponseEntity<Map<String, String>> unprogrammed() {
        return ResponseEntity.ok(roomService.unprogrammedCards());
    }

    @GetMapping("/rooms/{id}")
    public ResponseEntity<Map<String, Object>> getRoom(@PathVariable String id) {
        GameRoom room = roomService.getRoom(id);
        if (room == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(summary(room));
    }
}
