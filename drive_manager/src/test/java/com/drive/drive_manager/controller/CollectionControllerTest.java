package com.drive.drive_manager.controller;

import com.drive.drive_manager.service.CollectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The collection is the signed-in account's; a refused change says why. */
class CollectionControllerTest {

    private CollectionService collections;
    private CollectionController controller;

    @BeforeEach
    void setUp() {
        collections = mock(CollectionService.class);
        controller = new CollectionController(collections);
    }

    @Test
    void readsTheSignedInAccountsCollection() {
        CollectionService.View view = new CollectionService.View(Map.of("c1", Map.of("foil", 2)), Map.of("C", 40));
        when(collections.of("alice")).thenReturn(view);

        assertThat(controller.mine(player("alice")).getBody()).isEqualTo(view);
        assertThat(controller.mine(null).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void craftsForTheSignedInAccount() {
        CollectionService.Crafted crafted = new CollectionService.Crafted(List.of("plain"), new CollectionService.View(Map.of(), Map.of()));
        when(collections.craft("alice", "c1", 1)).thenReturn(crafted);

        assertThat(controller.craft(player("alice"), new CollectionController.CraftRequest("c1", 1)).getBody()).isEqualTo(crafted);
    }

    @Test
    void aRefusedChangeSaysWhy() {
        when(collections.breakCopies("alice", "c1", "foil", 3)).thenThrow(new CollectionService.CollectionException("No tienes esas copias"));

        var response = controller.breakCopies(player("alice"), new CollectionController.BreakRequest("c1", "foil", 3));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo(Map.of("message", "No tienes esas copias"));
    }

    @Test
    void onlyAnAdministratorSetsFragments() {
        var request = new CollectionController.FragmentsRequest(Map.of("C", 300));
        assertThat(controller.setFragments(player("alice"), "alice", request).getStatusCode().value()).isEqualTo(403);
        org.mockito.Mockito.verify(collections, org.mockito.Mockito.never()).setFragments(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap());

        CollectionService.View view = new CollectionService.View(Map.of(), Map.of("C", 300));
        when(collections.setFragments("bob", Map.of("C", 300))).thenReturn(view);
        Authentication admin = new UsernamePasswordAuthenticationToken("root", null, AuthorityUtils.createAuthorityList("manage_users"));
        assertThat(controller.setFragments(admin, "bob", request).getBody()).isEqualTo(view);
    }

    private static Authentication player(String id) {
        return new UsernamePasswordAuthenticationToken(id, null, AuthorityUtils.createAuthorityList("manage_decks"));
    }
}
