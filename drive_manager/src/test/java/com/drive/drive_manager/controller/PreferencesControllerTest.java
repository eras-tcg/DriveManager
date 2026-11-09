package com.drive.drive_manager.controller;

import com.drive.drive_manager.service.PreferencesService;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PreferencesControllerTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final PreferencesController controller = new PreferencesController(new PreferencesService(mongo));
    private final UsernamePasswordAuthenticationToken alice = new UsernamePasswordAuthenticationToken("alice", null, AuthorityUtils.NO_AUTHORITIES);

    @Test
    void preferencesAreSavedForThePlayer() {
        Map<String, Object> prefs = Map.of("theme", "dark", "eras-sim-style", 1, "eras-sim-mirrored", true, "eras-sim-hand-lift", 0.4);

        assertThat(controller.save(alice, Map.of("prefs", prefs)).getStatusCode().value()).isEqualTo(200);
        verify(mongo).upsert(any(), any(), eq("user_preferences"));
    }

    @Test
    void aBadKeyOrValueIsRefusedWhole() {
        assertThat(controller.save(alice, Map.of("prefs", Map.of("Bad Key", 1))).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.save(alice, Map.of("prefs", Map.of("theme", Map.of("nested", 1)))).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.save(alice, Map.of("prefs", Map.of("theme", "x".repeat(65)))).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.save(alice, Map.of("nothing", 1)).getStatusCode().value()).isEqualTo(400);
        verify(mongo, never()).upsert(any(), any(), anyString());
    }

    @Test
    void tooManyPreferencesAreRefused() {
        Map<String, Object> many = new HashMap<>();
        for (int i = 0; i <= PreferencesService.MAX_KEYS; i++) many.put("k" + i, i);

        assertThat(controller.save(alice, Map.of("prefs", many)).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void aPlayerWithoutPreferencesGetsNone() {
        assertThat(controller.mine(alice).getBody()).isEqualTo(Map.of("prefs", Map.of()));
    }
}
