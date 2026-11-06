package com.drive.drive_manager.simulator.replay;

import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.RoomService;
import org.bson.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The game history: the signed-in player's games, newest first, and one game (to its players and the
 * administrators, manage_users).
 */
@RestController
@RequestMapping("/api/simulator/matches")
public class MatchController {

    /** The most games a page holds. */
    static final int MAX_PAGE = 50;

    private final MatchHistory history;
    private final RoomService rooms;

    public MatchController(MatchHistory history, RoomService rooms) {
        this.history = history;
        this.rooms = rooms;
    }

    @GetMapping("/me")
    public ResponseEntity<List<Document>> mine(Authentication auth, @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(history.of(auth.getName(), page, Math.max(1, Math.min(MAX_PAGE, size))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Document> one(Authentication auth, @PathVariable String id) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(401).build();
        Document match = history.get(id);
        if (match == null) return ResponseEntity.notFound().build();
        return mayWatch(auth, match) ? ResponseEntity.ok(match) : ResponseEntity.status(403).build();
    }

    /**
     * Opens a room to watch the game again (its players and the administrators): {roomId, sameRules}; sameRules false
     * when the cards' rules changed since (the replay may differ). 404 when its log is gone.
     */
    @PostMapping("/{id}/replay")
    public ResponseEntity<Map<String, Object>> replay(Authentication auth, @PathVariable String id) throws java.io.IOException {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(401).build();
        Document match = history.get(id);
        if (match == null) return ResponseEntity.notFound().build();
        if (!mayWatch(auth, match)) return ResponseEntity.status(403).build();
        MatchHistory.Replayable log = history.log(id);
        if (log == null) return ResponseEntity.status(404).body(Map.of("message", "La repetición de esta partida ya no está disponible"));
        GameRoom room = rooms.openReplay(auth.getName(), log.setup(), log.actions());
        return ResponseEntity.ok(Map.of("roomId", room.getRoomId(), "sameRules", log.sameRules()));
    }

    /** Its players and the administrators (manage_users). */
    private static boolean mayWatch(Authentication auth, Document match) {
        boolean player = match.getList("playerIds", String.class, List.of()).contains(auth.getName());
        return player || auth.getAuthorities().stream().anyMatch(a -> "manage_users".equals(a.getAuthority()));
    }
}
