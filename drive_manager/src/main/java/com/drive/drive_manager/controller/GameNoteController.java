package com.drive.drive_manager.controller;

import com.drive.drive_manager.dto.GameNote;
import com.drive.drive_manager.repository.GameNoteRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * GET    /api/drive/game-notes      — list all notes (requires manage_decks)
 * POST   /api/drive/game-notes      — add a note    (requires manage_decks)
 * PUT    /api/drive/game-notes/{id} — update a note (requires manage_decks)
 * DELETE /api/drive/game-notes/{id} — delete a note (requires manage_decks)
 */
@RestController
@RequestMapping("/api/drive/game-notes")
public class GameNoteController {

    private final GameNoteRepository repo;

    public GameNoteController(GameNoteRepository repo) {
        this.repo = repo;
    }

    @PreAuthorize("hasAuthority('manage_decks')")
    @GetMapping
    public ResponseEntity<List<GameNote>> list() {
        return ResponseEntity.ok(repo.findAllByOrderByCreatedAtDesc());
    }

    @PreAuthorize("hasAuthority('manage_decks')")
    @PostMapping
    public ResponseEntity<Object> create(@RequestBody GameNote body) {
        if (body.getText() == null || body.getText().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "text is required"));
        body.setId(null);
        body.setCreatedAt(Instant.now());
        return ResponseEntity.ok(repo.save(body));
    }

    @PreAuthorize("hasAuthority('manage_decks')")
    @PutMapping("/{id}")
    public ResponseEntity<Object> update(@PathVariable String id, @RequestBody GameNote body) {
        return repo.findById(id).map(existing -> {
            if (body.getText() == null || body.getText().isBlank())
                return ResponseEntity.badRequest().body((Object) Map.of("error", "text is required"));
            body.setId(id);
            body.setCreatedAt(existing.getCreatedAt());
            return ResponseEntity.ok((Object) repo.save(body));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('manage_decks')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Object> delete(@PathVariable String id) {
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
