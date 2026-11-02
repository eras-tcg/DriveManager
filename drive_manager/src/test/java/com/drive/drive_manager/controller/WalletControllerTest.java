package com.drive.drive_manager.controller;

import com.drive.drive_manager.service.ShopService;
import com.drive.drive_manager.service.WalletRules;
import com.drive.drive_manager.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The wallet belongs to the signed-in account; giving gems is for administrators only. */
class WalletControllerTest {

    private WalletService wallets;
    private ShopService shop;
    private WalletController controller;

    @BeforeEach
    void setUp() {
        wallets = mock(WalletService.class);
        shop = mock(ShopService.class);
        controller = new WalletController(wallets, shop);
    }

    @Test
    void readsTheSignedInAccountsWallet() {
        when(wallets.open("alice")).thenReturn(new WalletRules.Opened(new WalletRules.State(2700, "2026-11-02"), 300));

        var response = controller.mine(player("alice"));

        assertThat(response.getBody()).containsEntry("gems", 2700).containsEntry("reward", 300);
    }

    @Test
    void buysForTheSignedInAccount() {
        when(shop.buy("alice", "E2", "box")).thenReturn(new ShopService.Opening(100, 2400, java.util.List.of()));

        var response = controller.buy(player("alice"), new WalletController.PurchaseRequest("E2", "box"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("gems", 100).containsEntry("price", 2400).containsEntry("packs", java.util.List.of());
    }

    @Test
    void aRefusedPurchaseSaysWhy() {
        when(shop.buy("alice", "E2", "box")).thenThrow(new WalletService.PurchaseException("No te alcanzan las gemas"));

        var response = controller.buy(player("alice"), new WalletController.PurchaseRequest("E2", "box"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("message", "No te alcanzan las gemas");
    }

    @Test
    void onlyAnAdministratorGivesGems() {
        assertThat(controller.grant(player("alice"), "alice", new WalletController.GrantRequest(10000)).getStatusCode().value()).isEqualTo(403);
        verify(wallets, never()).grant(anyString(), anyInt());

        when(wallets.grant("bob", 10000)).thenReturn(12400);
        Authentication admin = new UsernamePasswordAuthenticationToken("root", null, AuthorityUtils.createAuthorityList("manage_users"));
        assertThat(controller.grant(admin, "bob", new WalletController.GrantRequest(10000)).getBody()).containsEntry("gems", 12400);
    }

    private static Authentication player(String id) {
        return new UsernamePasswordAuthenticationToken(id, null, AuthorityUtils.createAuthorityList("manage_decks"));
    }
}
