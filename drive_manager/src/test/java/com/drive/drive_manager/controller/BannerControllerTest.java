package com.drive.drive_manager.controller;

import com.drive.drive_manager.service.BannerService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BannerControllerTest {

    private final BannerService banners = mock(BannerService.class);
    private final BannerController controller = new BannerController(banners);
    private final UsernamePasswordAuthenticationToken alice = new UsernamePasswordAuthenticationToken("alice", null, AuthorityUtils.NO_AUTHORITIES);

    @Test
    void anUploadedBannerGivesItsUrl() throws Exception {
        when(banners.put(any(), any())).thenReturn("https://cdn/banners/alice.jpg?v=1");

        var answer = controller.upload(alice, new byte[]{1, 2, 3});
        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getBody()).containsEntry("url", "https://cdn/banners/alice.jpg?v=1");
    }

    @Test
    void anEmptyOrTooBigImageIsRefused() throws Exception {
        assertThat(controller.upload(alice, new byte[0]).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.upload(alice, new byte[BannerService.MAX_BYTES + 1]).getStatusCode().value()).isEqualTo(400);
        verify(banners, never()).put(any(), any());
    }

    @Test
    void aRefusedImageSaysWhy() throws Exception {
        when(banners.put(any(), any())).thenThrow(new IllegalArgumentException("No es una imagen"));

        assertThat(controller.upload(alice, new byte[]{1}).getStatusCode().value()).isEqualTo(400);
    }
}
