package com.drive.drive_manager.simulator.room;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/** Solo mode (test rooms) is open to every signed-in player, not only to content editors. */
class RoomRestControllerTest {

    private RoomService rooms;
    private RoomRestController controller;

    @BeforeEach
    void setUp() {
        rooms = mock(RoomService.class);
        controller = new RoomRestController();
        ReflectionTestUtils.setField(controller, "roomService", rooms);
    }

    @Test
    void aPlayerWithoutEditPermissionsCreatesATestRoom() {
        GameRoom room = new GameRoom();
        room.setRoomId("r1");
        when(rooms.createRoom("alice", RoomOptions.TEST)).thenReturn(room);

        var response = controller.createRoom(player("alice"), true, false, false);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("roomId", "r1");
        verify(rooms).createRoom("alice", RoomOptions.TEST);
    }

    @Test
    void aPlayerWithoutEditPermissionsPlaysAlone() {
        when(rooms.makeTestRoom("r1", "alice")).thenReturn(null);

        var response = controller.playAlone("r1", player("alice"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(rooms).makeTestRoom("r1", "alice");
    }

    @Test
    void withoutASessionNoRoomIsCreated() {
        assertThat(controller.createRoom(null, true, false, false).getStatusCode().value()).isEqualTo(401);
        verify(rooms, never()).createRoom(anyString(), any(RoomOptions.class));
    }

    private static Authentication player(String id) {
        return new UsernamePasswordAuthenticationToken(id, null, AuthorityUtils.createAuthorityList("manage_decks"));
    }
}
