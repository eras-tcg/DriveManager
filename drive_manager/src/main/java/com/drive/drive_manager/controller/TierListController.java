package com.drive.drive_manager.controller;

import com.drive.drive_manager.dto.TierList;
import com.drive.drive_manager.repository.TierListRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * GET /api/drive/tierlist  — fetch the shared tierlist (public)
 * PUT /api/drive/tierlist  — upsert the shared tierlist (requires manage_decks)
 */
@RestController
@RequestMapping("/api/drive/tierlist")
public class TierListController {

    private final TierListRepository repo;

    public TierListController(TierListRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public ResponseEntity<TierList> get() {
        return repo.findAll().stream().findFirst()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(defaultTierList()));
    }

    @PreAuthorize("hasAuthority('manage_decks')")
    @PutMapping
    public ResponseEntity<TierList> save(@RequestBody TierList body) {
        body.setUpdatedAt(Instant.now());
        repo.findAll().stream().findFirst()
                .ifPresent(existing -> body.setId(existing.getId()));
        return ResponseEntity.ok(repo.save(body));
    }

    private TierList defaultTierList() {
        List<TierList.TierRow> defaultTiers = List.of(
                new TierList.TierRow("S", "#FF7675", new ArrayList<>()),
                new TierList.TierRow("A", "#FDCB6E", new ArrayList<>()),
                new TierList.TierRow("B", "#55EFC4", new ArrayList<>()),
                new TierList.TierRow("C", "#74B9FF", new ArrayList<>()),
                new TierList.TierRow("D", "#B2BEC3", new ArrayList<>())
        );
        TierList.TierPage defaultPage = new TierList.TierPage("1", "Principal", defaultTiers);
        return new TierList(null, List.of(defaultPage), Instant.now());
    }
}
