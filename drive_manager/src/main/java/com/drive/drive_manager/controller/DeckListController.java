package com.drive.drive_manager.controller;

import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.repository.DeckListRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * GET    /api/drive/decklists          — list all
 * GET    /api/drive/decklists/{id}     — get one
 * POST   /api/drive/decklists          — create
 * PUT    /api/drive/decklists/{id}     — update
 * DELETE /api/drive/decklists/{id}     — delete
 */
@RestController
@RequestMapping("/api/drive/decklists")
public class DeckListController {

    private final DeckListRepository repo;

    public DeckListController(DeckListRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public ResponseEntity<List<DeckList>> listAll() {
        return ResponseEntity.ok(repo.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeckList> getOne(@PathVariable String id) {
        return repo.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Object> create(@RequestBody DeckList body) {
        String err = validate(body);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));

        body.setId(null);
        if (body.getCreatedAt() == null) body.setCreatedAt(Instant.now());
        return ResponseEntity.ok(repo.save(body));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Object> update(@PathVariable String id,
                                         @RequestBody DeckList body) {
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        String err = validate(body);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));

        body.setId(id);
        return ResponseEntity.ok(repo.save(body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
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
