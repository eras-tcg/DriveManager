package com.drive.drive_manager.controller;

import com.drive.drive_manager.dto.Game;
import com.drive.drive_manager.repository.GameRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * GET    /api/drive/games      — list all games (requires manage_decks)
 * POST   /api/drive/games      — register a game (requires manage_decks)
 * PUT    /api/drive/games/{id} — update a game  (requires manage_decks)
 * DELETE /api/drive/games/{id} — delete a game  (requires manage_decks)
 */
@RestController
@RequestMapping("/api/drive/games")
public class GameController {

    private final GameRepository repo;

    public GameController(GameRepository repo) {
        this.repo = repo;
    }

    @PreAuthorize("hasAuthority('manage_decks')")
    @GetMapping
    public ResponseEntity<List<Game>> list() {
        return ResponseEntity.ok(repo.findAllByOrderByCreatedAtDesc());
    }

    @PreAuthorize("hasAuthority('manage_decks')")
    @PostMapping
    public ResponseEntity<Object> create(@RequestBody Game body) {
        String err = validate(body);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        body.setId(null);
        body.setCreatedAt(Instant.now());
        return ResponseEntity.ok(repo.save(body));
    }

    @PreAuthorize("hasAuthority('manage_decks')")
    @PutMapping("/{id}")
    public ResponseEntity<Object> update(@PathVariable String id, @RequestBody Game body) {
        return repo.findById(id).map(existing -> {
            String err = validate(body);
            if (err != null) return ResponseEntity.badRequest().body((Object) Map.of("error", err));
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

    private String validate(Game g) {
        if (g.getPlayer1() == null || g.getPlayer2() == null) return "Both players are required";
        if (isBlank(g.getPlayer1().getPlayerName())) return "Player 1 name is required";
        if (isBlank(g.getPlayer2().getPlayerName())) return "Player 2 name is required";
        if (!List.of("player1", "player2").contains(g.getWinner())) return "winner must be player1 or player2";
        if (!List.of("player1", "player2").contains(g.getFirstPlayer())) return "firstPlayer must be player1 or player2";
        return null;
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }
}
