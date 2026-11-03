package com.drive.drive_manager.controller;

import com.drive.drive_manager.dto.ShopOdds;
import com.drive.drive_manager.service.ShopOddsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The shop's odds (ShopOdds), shown in the shop's odds table and edited by whoever manages editions:
 * GET /api/drive/shop/odds — the odds in force
 * PUT /api/drive/shop/odds — new odds (each from 0 to 1); answers them, or 400 with the reason
 */
@RestController
@RequestMapping("/api/drive/shop/odds")
public class ShopOddsController {

    private final ShopOddsService odds;

    public ShopOddsController(ShopOddsService odds) {
        this.odds = odds;
    }

    @GetMapping
    public ShopOdds current() {
        return odds.current();
    }

    @PreAuthorize("hasAuthority('manage_editions')")
    @PutMapping
    public ResponseEntity<Object> save(@RequestBody ShopOdds request) {
        try {
            return ResponseEntity.ok(odds.save(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
