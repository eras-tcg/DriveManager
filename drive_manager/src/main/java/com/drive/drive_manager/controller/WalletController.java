package com.drive.drive_manager.controller;

import com.drive.drive_manager.service.ActivityLogService;
import com.drive.drive_manager.service.ProgressionRules;
import com.drive.drive_manager.service.ProgressionService;
import com.drive.drive_manager.service.ShopService;
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
    private final ShopService shop;
    private final ProgressionService progression;
    private final ActivityLogService activity;

    public WalletController(WalletService wallets, ShopService shop, ProgressionService progression, ActivityLogService activity) {
        this.wallets = wallets;
        this.shop = shop;
        this.progression = progression;
        this.activity = activity;
    }

    /**
     * {gems, reward, structures}: reward is what the daily reward just added (0 when nothing); structures, the structure
     * decks already bought (each only once).
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> mine(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(401).build();
        WalletRules.Opened opened = wallets.open(auth.getName());
        return ResponseEntity.ok(Map.of("gems", opened.state().gems(), "reward", opened.reward(), "structures", wallets.structures(auth.getName())));
    }

    public record PurchaseRequest(String editionId, String product) {
    }

    /**
     * Buys a "pack", a "box" or a "structure" deck, drawn here and added to the player's collection: {gems, price,
     * packs}, or 400 with a message (not for sale, not enough gems).
     */
    @PostMapping("/me/purchase")
    public ResponseEntity<Map<String, Object>> buy(Authentication auth, @RequestBody PurchaseRequest request) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(401).build();
        try {
            ShopService.Opening opening = shop.buy(auth.getName(), request.editionId(), request.product());
            // The packs opened (a box's every pack; a structure deck is no pack) count for the progression missions
            if (!"structure".equals(request.product())) progression.record(auth.getName(), ProgressionRules.PACK_OPENED, opening.packs().size());
            activity.record(auth.getName(), ActivityLogService.SHOP_PURCHASE,
                    Map.of("editionId", String.valueOf(request.editionId()), "product", String.valueOf(request.product()), "price", opening.price()));
            return ResponseEntity.ok(Map.of("gems", opening.gems(), "price", opening.price(), "packs", opening.packs()));
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
