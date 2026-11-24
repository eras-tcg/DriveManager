package com.drive.drive_manager.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A structure deck's public deck is found by its code, alone or before the deck's name. */
class CardPoolServiceNamingTest {

    @Test
    void theCodeAloneOrBeforeTheNameNamesTheDeck() {
        assertThat(CardPoolService.namedAfter(" st1 ", "ST1")).isTrue();
        assertThat(CardPoolService.namedAfter("ST1 Saltadores del multiverso", "ST1")).isTrue();
        assertThat(CardPoolService.namedAfter("ST10 Otro", "ST1")).isFalse();
        assertThat(CardPoolService.namedAfter("E1.1 Caballeros (ST 4 (copia)", "ST4")).isFalse();
        assertThat(CardPoolService.namedAfter(null, "ST1")).isFalse();
    }
}
