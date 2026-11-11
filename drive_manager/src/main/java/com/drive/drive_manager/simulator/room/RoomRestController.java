package com.drive.drive_manager.simulator.room;

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

    /**
     * A room owned by its creator; {@code editor}: a test room ("solo mode"), played alone on both sides; {@code bot}: a
     * game against the server's bot; {@code ranked}: its result moves the players' rank (not a test room). Open to every player.
     */
    @PostMapping("/rooms")
    public ResponseEntity<Map<String, String>> createRoom(Authentication auth, @RequestParam(defaultValue = "false") boolean editor,
                                                          @RequestParam(defaultValue = "false") boolean bot,
                                                          @RequestParam(defaultValue = "false") boolean ranked) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }
        GameRoom room = roomService.createRoom(auth.getName(), new RoomOptions(editor, bot && !editor, ranked && !editor));
        return ResponseEntity.ok(Map.of("roomId", room.getRoomId()));
    }

    /** The owner, waiting alone, plays both sides: the room becomes a test room. */
    @PostMapping("/rooms/{id}/solo")
    public ResponseEntity<Map<String, String>> playAlone(@PathVariable String id, Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(401).build();
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
        synchronized (room) {
            return summaryOf(room);
        }
    }

    private Map<String, Object> summaryOf(GameRoom room) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("roomId",      room.getRoomId());
        body.put("status",      room.getStatus().name());
        body.put("ranked",      room.isRanked());
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

    @Autowired
    private CardNameCheck cardNameCheck;

    /**
     * Card names that do not match between the metadata and the programmed cards: metadata names to write as printed (they play,
     * found by accents and hyphens aside) and card files no metadata card names (the engine never plays them).
     */
    @GetMapping("/card-names")
    public ResponseEntity<CardNameCheck.Report> cardNames() {
        return ResponseEntity.ok(cardNameCheck.check());
    }

    @GetMapping("/rooms/{id}")
    public ResponseEntity<Map<String, Object>> getRoom(@PathVariable String id) {
        GameRoom room = roomService.getRoom(id);
        if (room == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(summary(room));
    }
}
