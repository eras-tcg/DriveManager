package com.drive.drive_manager.controller;

import com.drive.drive_manager.service.MissionService;
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

/** The missions are the signed-in account's; a refused claim says why. */
class MissionControllerTest {

    private MissionService missions;
    private MissionController controller;

    @BeforeEach
    void setUp() {
        missions = mock(MissionService.class);
        controller = new MissionController(missions);
    }

    @Test
    void readsTheSignedInAccountsMissions() {
        MissionService.Board board = new MissionService.Board(List.of(new MissionService.MissionView("win", 0, 1, 100, false)), List.of(), "2026-11-03", "2026-11-09");
        when(missions.open("alice")).thenReturn(board);

        assertThat(controller.mine(player("alice")).getBody()).isEqualTo(board);
        assertThat(controller.mine(null).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void aRefusedClaimSaysWhy() {
        when(missions.claim("alice", true, "win")).thenThrow(new MissionService.MissionException("Esa misión todavía no está cumplida, o ya la reclamaste"));

        var response = controller.claim(player("alice"), new MissionController.ClaimRequest("weekly", "win"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo(Map.of("message", "Esa misión todavía no está cumplida, o ya la reclamaste"));
    }

    private static Authentication player(String id) {
        return new UsernamePasswordAuthenticationToken(id, null, AuthorityUtils.createAuthorityList("manage_decks"));
    }
}
