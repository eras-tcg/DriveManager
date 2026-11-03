package com.drive.drive_manager.controller;

import com.drive.drive_manager.service.AvatarService;
import com.drive.drive_manager.service.ProgressionRules;
import com.drive.drive_manager.service.ProgressionService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AvatarControllerTest {

    private final AvatarService avatars = mock(AvatarService.class);
    private final ProgressionService progression = mock(ProgressionService.class);
    private final AvatarController controller = new AvatarController(avatars, progression);
    private final UsernamePasswordAuthenticationToken alice = new UsernamePasswordAuthenticationToken("alice", null, AuthorityUtils.NO_AUTHORITIES);

    @Test
    void anUploadedIconCountsForTheProgression() throws Exception {
        when(avatars.put(any(), any())).thenReturn("https://cdn/avatars/alice.webp");

        assertThat(controller.upload(alice, new byte[]{1, 2, 3}).getStatusCode().value()).isEqualTo(200);
        verify(progression).record("alice", ProgressionRules.AVATAR_SET, 1);
    }

    @Test
    void aRefusedImageCountsForNothing() throws Exception {
        when(avatars.put(any(), any())).thenThrow(new IllegalArgumentException("No es una imagen"));

        assertThat(controller.upload(alice, new byte[]{1}).getStatusCode().value()).isEqualTo(400);
        verify(progression, never()).record(anyString(), anyString(), anyInt());
    }
}
