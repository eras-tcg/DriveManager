package com.drive.drive_manager.controller;

import com.drive.drive_manager.service.CollectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.function.Supplier;

/**
 * The signed-in player's cards and fragments: read them, break copies, craft cards (a number of them, or until one
 * comes out in a finish), and break every extra copy. A refused change answers 400 with a message. Administrators
 * (manage_users) can set an account's fragments, to test.
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

    /** `maxFragments`: the most the player will spend (all of them when absent). */
    public record CraftManyRequest(Map<String, Integer> cards) {
    }

    /** Crafts several cards at once (card id → copies): all of them or none. */
    @PostMapping("/me/craft-many")
    public ResponseEntity<?> craftMany(Authentication auth, @RequestBody CraftManyRequest request) {
        return run(auth, () -> collections.craftMany(auth.getName(), request.cards() == null ? Map.of() : request.cards()));
    }

    public record CraftUntilRequest(String cardId, String finish, Integer maxFragments) {
    }

    @PostMapping("/me/craft-until")
    public ResponseEntity<?> craftUntil(Authentication auth, @RequestBody CraftUntilRequest request) {
        return run(auth, () -> collections.craftUntil(auth.getName(), request.cardId(), request.finish(), request.maxFragments()));
    }

    @PostMapping("/me/break-extras")
    public ResponseEntity<?> breakExtras(Authentication auth) {
        return run(auth, () -> collections.breakExtras(auth.getName()));
    }

    public record FragmentsRequest(Map<String, Integer> fragments) {
    }

    /** Sets an account's fragments of some rarities: manage_users only. */
    @PostMapping("/{userId}/fragments")
    public ResponseEntity<?> setFragments(Authentication auth, @PathVariable String userId, @RequestBody FragmentsRequest request) {
        if (auth != null && auth.getAuthorities().stream().noneMatch(a -> "manage_users".equals(a.getAuthority()))) return ResponseEntity.status(403).build();
        return run(auth, () -> collections.setFragments(userId, request.fragments() == null ? Map.of() : request.fragments()));
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
