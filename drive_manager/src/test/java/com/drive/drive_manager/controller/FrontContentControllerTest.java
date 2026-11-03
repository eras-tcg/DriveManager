package com.drive.drive_manager.controller;

import com.drive.drive_manager.dto.HomeUpdateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.assertj.core.api.Assertions.assertThat;

class FrontContentControllerTest {

    @Test
    void onlyTheHomeEditorsChangeTheHome() throws Exception {
        PreAuthorize rule = FrontContentController.class.getMethod("updateHome", HomeUpdateRequest.class)
                .getAnnotation(PreAuthorize.class);
        assertThat(rule).isNotNull();
        assertThat(rule.value()).isEqualTo("hasAuthority('manage_home')");
    }
}
