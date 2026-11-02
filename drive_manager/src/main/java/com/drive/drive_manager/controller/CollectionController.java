package com.drive.drive_manager.controller;

import com.drive.drive_manager.service.CollectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.function.Supplier;

/**
 * The signed-in player's cards and fragments: read them, break copies, craft cards (a number of them, or until one
 * comes out in a finish), and break every extra copy. A refused change answers 400 with a message.
 */
@RestController
@RequestMapping("/api/drive/collection")
public class CollectionController {

    private final CollectionService collections;

    public CollectionController(CollectionService collections) {
        this.collections = collections;
    }

    @GetMapping("/me")
    public ResponseEntity<?> mine(Authentication auth) {
        return run(auth, () -> collections.of(auth.getName()));
    }

    public record BreakRequest(String cardId, String finish, int count) {
    }

    @PostMapping("/me/break")
    public ResponseEntity<?> breakCopies(Authentication auth, @RequestBody BreakRequest request) {
        return run(auth, () -> collections.breakCopies(auth.getName(), request.cardId(), request.finish(), request.count()));
    }

    public record CraftRequest(String cardId, int count) {
    }

    @PostMapping("/me/craft")
    public ResponseEntity<?> craft(Authentication auth, @RequestBody CraftRequest request) {
        return run(auth, () -> collections.craft(auth.getName(), request.cardId(), request.count()));
    }

    public record CraftUntilRequest(String cardId, String finish) {
    }

    @PostMapping("/me/craft-until")
    public ResponseEntity<?> craftUntil(Authentication auth, @RequestBody CraftUntilRequest request) {
        return run(auth, () -> collections.craftUntil(auth.getName(), request.cardId(), request.finish()));
    }

    @PostMapping("/me/break-extras")
    public ResponseEntity<?> breakExtras(Authentication auth) {
        return run(auth, () -> collections.breakExtras(auth.getName()));
    }

    private static ResponseEntity<?> run(Authentication auth, Supplier<Object> action) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(401).build();
        try {
            return ResponseEntity.ok(action.get());
        } catch (CollectionService.CollectionException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
