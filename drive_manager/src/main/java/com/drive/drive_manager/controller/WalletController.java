package com.drive.drive_manager.controller;

import com.drive.drive_manager.service.WalletRules;
import com.drive.drive_manager.service.WalletService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The signed-in player's simulated gems for the Editions shop. GET gives the balance (and the daily reward, once a
 * day); a purchase charges the server's price. Administrators (manage_users) can give gems to test the shop.
 */
@RestController
@RequestMapping("/api/drive/wallet")
public class WalletController {

    private final WalletService wallets;

    public WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    /** {gems, reward}: reward is what the daily reward just added (0 when nothing). */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Integer>> mine(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(401).build();
        WalletRules.Opened opened = wallets.open(auth.getName());
        return ResponseEntity.ok(Map.of("gems", opened.state().gems(), "reward", opened.reward()));
    }

    public record PurchaseRequest(String editionId, String product) {
    }

    /** Buys a "pack" or a "box" of an edition: {gems, price}, or 400 with a message (not for sale, not enough gems). */
    @PostMapping("/me/purchase")
    public ResponseEntity<Map<String, Object>> buy(Authentication auth, @RequestBody PurchaseRequest request) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(401).build();
        try {
            WalletService.Purchase purchase = wallets.buy(auth.getName(), request.editionId(), request.product());
            return ResponseEntity.ok(Map.of("gems", purchase.gems(), "price", purchase.price()));
        } catch (WalletService.PurchaseException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    public record GrantRequest(int gems) {
    }

    /** Gives (or takes, negative) gems to an account, to test the shop: manage_users only. */
    @PostMapping("/{userId}/grant")
    public ResponseEntity<Map<String, Integer>> grant(Authentication auth, @PathVariable String userId, @RequestBody GrantRequest request) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(401).build();
        if (auth.getAuthorities().stream().noneMatch(a -> "manage_users".equals(a.getAuthority()))) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(Map.of("gems", wallets.grant(userId, request.gems())));
    }
}
