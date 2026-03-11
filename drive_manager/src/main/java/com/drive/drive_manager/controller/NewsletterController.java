package com.drive.drive_manager.controller;

import com.drive.drive_manager.dto.Newsletter;
import com.drive.drive_manager.repository.NewsletterRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * GET    /api/drive/newsletter          — list all (newest first)
 * GET    /api/drive/newsletter/{id}     — get one
 * POST   /api/drive/newsletter          — create
 * PUT    /api/drive/newsletter/{id}     — update
 * DELETE /api/drive/newsletter/{id}     — delete
 */
@RestController
@RequestMapping("/api/drive/newsletter")
public class NewsletterController {

    private final NewsletterRepository repo;

    public NewsletterController(NewsletterRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public ResponseEntity<List<Newsletter>> listAll() {
        return ResponseEntity.ok(repo.findAllByOrderByPublishedAtDesc());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Newsletter> getOne(@PathVariable String id) {
        return repo.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('manage_newsletter')")
    @PostMapping
    public ResponseEntity<Newsletter> create(@RequestBody Newsletter body) {
        body.setId(null); // force new document
        if (body.getPublishedAt() == null) body.setPublishedAt(Instant.now());
        return ResponseEntity.ok(repo.save(body));
    }

    @PreAuthorize("hasAuthority('manage_newsletter')")
    @PutMapping("/{id}")
    public ResponseEntity<Newsletter> update(@PathVariable String id,
                                             @RequestBody Newsletter body) {
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        body.setId(id);
        return ResponseEntity.ok(repo.save(body));
    }

    @PreAuthorize("hasAuthority('manage_newsletter')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
