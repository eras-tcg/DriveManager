package com.drive.drive_manager.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The card back a player chose, read from their preferences to show it to both players at the table. */
class PreferencesServiceCardBackTest {

    @Test
    void theChosenBackIsItsId() {
        assertThat(PreferencesService.cardBackIn(Map.of("eras-card-back", "clasico", "theme", "dark"))).isEqualTo("clasico");
    }

    @Test
    void noneOrAnImplausibleValueIsTheDefault() {
        assertThat(PreferencesService.cardBackIn(Map.of())).isNull();
        assertThat(PreferencesService.cardBackIn(Map.of("eras-card-back", 3))).isNull();
        assertThat(PreferencesService.cardBackIn(Map.of("eras-card-back", "https://evil.example/back.png"))).isNull();
        assertThat(PreferencesService.cardBackIn(Map.of("eras-card-back", "x".repeat(33)))).isNull();
    }
}
