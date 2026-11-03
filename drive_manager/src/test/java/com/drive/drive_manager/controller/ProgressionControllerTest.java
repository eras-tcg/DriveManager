package com.drive.drive_manager.controller;

import com.drive.drive_manager.service.ProgressionService;
import com.drive.drive_manager.service.ShopService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Progression is the signed-in account's; a claim answers the board (and the structure deck given), or why not. */
class ProgressionControllerTest {

    private ProgressionService progression;
    private ProgressionController controller;
    private final ProgressionService.Board board = new ProgressionService.Board(List.of(), List.of(), List.of());

    @BeforeEach
    void setUp() {
        progression = mock(ProgressionService.class);
        controller = new ProgressionController(progression);
    }

    @Test
    void readsTheSignedInAccountsProgression() {
        when(progression.open("alice")).thenReturn(board);

        assertThat(controller.mine(player("alice")).getBody()).isEqualTo(board);
        assertThat(controller.mine(null).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void aWelcomeClaimAnswersTheStructureDeckGiven() {
        ShopService.Opening opening = new ShopService.Opening(2400, 0, List.of());
        when(progression.claimWelcome("alice", "structure", "ST1")).thenReturn(new ProgressionService.Claimed(board, opening));

        var response = controller.claim(player("alice"), new ProgressionController.ClaimRequest("welcome", "structure", "ST1"));

        assertThat(response.getBody()).isEqualTo(Map.of("board", board, "opening", opening));
    }

    @Test
    void aRefusedMilestoneClaimSaysWhy() {
        when(progression.claimMilestone("alice", "wins")).thenThrow(new ProgressionService.ProgressionException("Ese nivel todavía no está cumplido"));

        var response = controller.claim(player("alice"), new ProgressionController.ClaimRequest("milestone", "wins", null));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo(Map.of("message", "Ese nivel todavía no está cumplido"));
    }

    @Test
    void aVisitIsTheSignedInAccounts() {
        controller.visit(player("alice"), new ProgressionController.VisitRequest("como-jugar"));

        verify(progression).visit("alice", "como-jugar");
    }

    private static Authentication player(String id) {
        return new UsernamePasswordAuthenticationToken(id, null, AuthorityUtils.createAuthorityList("manage_decks"));
    }
}
