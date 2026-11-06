package com.drive.drive_manager.simulator.rank;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ranked queue of Online: entering it, asking how the search goes (every moment: who stops asking leaves it) and
 * leaving it. Once paired, the answer has the room made for the two players.
 */
@RestController
@RequestMapping("/api/simulator/queue")
public class QueueController {

    private final RankedQueue queue;

    public QueueController(RankedQueue queue) {
        this.queue = queue;
    }

    /** {@code username}: the name shown to the rival. */
    public record JoinRequest(String username) {}

    @PostMapping
    public ResponseEntity<RankedQueue.Status> join(Authentication auth, @RequestBody(required = false) JoinRequest body) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(401).build();
        String name = body == null || body.username() == null ? auth.getName() : body.username();
        return ResponseEntity.ok(queue.join(auth.getName(), name, System.currentTimeMillis()));
    }

    @GetMapping
    public ResponseEntity<RankedQueue.Status> poll(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(queue.poll(auth.getName(), System.currentTimeMillis()));
    }

    @DeleteMapping
    public ResponseEntity<Void> leave(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(401).build();
        queue.leave(auth.getName());
        return ResponseEntity.noContent().build();
    }
}
