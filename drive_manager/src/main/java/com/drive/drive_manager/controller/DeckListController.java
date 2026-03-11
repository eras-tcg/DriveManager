package com.drive.drive_manager.controller;

import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.repository.DeckListRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * GET    /api/drive/decklists          — list all (public)
 * GET    /api/drive/decklists/{id}     — get one (public)
 * POST   /api/drive/decklists          — create (requires manage_decks)
 * PUT    /api/drive/decklists/{id}     — update (requires manage_decks + ownership)
 * DELETE /api/drive/decklists/{id}     — delete (requires manage_decks + ownership)
 */
@RestController
@RequestMapping("/api/drive/decklists")
public class DeckListController {

    private final DeckListRepository repo;

    public DeckListController(DeckListRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public ResponseEntity<List<DeckList>> listAll(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) boolean publicDecks) {

        if (publicDecks) {
            return ResponseEntity.ok(repo.findPublicDecks());
        }
        if (userId != null && !userId.isBlank()) {
            return ResponseEntity.ok(repo.findByUserId(userId));
        }
        return ResponseEntity.ok(repo.findAll());
    }



    @GetMapping("/{id}")
    public ResponseEntity<DeckList> getOne(@PathVariable String id) {
        return repo.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('manage_decks')")
    @PostMapping
    public ResponseEntity<Object> create(@RequestBody DeckList body, Authentication auth) {
        String err = validate(body);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));

        String callerId = auth.getName();
        if (body.getUserId() == null || body.getUserId().isBlank()) {
            body.setUserId(callerId);
        } else if (!body.getUserId().equals(callerId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Cannot create a deck for another user"));
        }

        body.setId(null);
        if (body.getCreatedAt() == null) body.setCreatedAt(Instant.now());
        return ResponseEntity.ok(repo.save(body));
    }

    @PreAuthorize("hasAuthority('manage_decks')")
    @PutMapping("/{id}")
    public ResponseEntity<Object> update(@PathVariable String id,
                                         @RequestBody DeckList body,
                                         Authentication auth) {
        Optional<DeckList> existing = repo.findById(id);
        if (existing.isEmpty()) return ResponseEntity.notFound().build();

        String callerId = auth.getName();
        if (!callerId.equals(existing.get().getUserId())) {
            return ResponseEntity.status(403).body(Map.of("error", "Cannot edit another user's deck"));
        }

        String err = validate(body);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));

        body.setId(id);
        body.setUserId(existing.get().getUserId());
        return ResponseEntity.ok(repo.save(body));
    }

    @PreAuthorize("hasAuthority('manage_decks')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Object> delete(@PathVariable String id, Authentication auth) {
        Optional<DeckList> existing = repo.findById(id);
        if (existing.isEmpty()) return ResponseEntity.notFound().build();

        String callerId = auth.getName();
        if (!callerId.equals(existing.get().getUserId())) {
            return ResponseEntity.status(403).body(Map.of("error", "Cannot delete another user's deck"));
        }

        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private String validate(DeckList body) {
        if (body.getCards() == null || body.getCards().isEmpty()) {
            return "cards list must not be empty";
        }
        if (body.getDeckImage() != null && !body.getCards().contains(body.getDeckImage())) {
            return "deckImage must be a card code present in the cards list";
        }
        return null;
    }
}
