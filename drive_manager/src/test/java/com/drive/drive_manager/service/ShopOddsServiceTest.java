package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.ShopOdds;
import com.drive.drive_manager.repository.ShopOddsRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The shop's odds live in the database, with the old ones until changed, and only sensible ones are saved. */
class ShopOddsServiceTest {

    private final ShopOddsRepository repository = mock(ShopOddsRepository.class);
    private final ShopOddsService service = new ShopOddsService(repository);

    @Test
    void untilSavedTheyAreTheDefaults() {
        when(repository.findById(ShopOdds.ID)).thenReturn(Optional.empty());
        assertThat(service.current()).isEqualTo(ShopOdds.defaults());
        assertThat(ShopOdds.defaults().getFoil()).isEqualTo(0.05);
        assertThat(ShopOdds.defaults().getAlt()).isEqualTo(0.0001);
    }

    @Test
    void savedOnesAreReadBack() {
        ShopOdds saved = new ShopOdds(ShopOdds.ID, 0.1, 0.01, 0.001, 1, 0.25);
        when(repository.findById(ShopOdds.ID)).thenReturn(Optional.of(saved));
        assertThat(service.current()).isEqualTo(saved);
    }

    @Test
    void theyAreSavedUnderTheirOneIdOnlyWhenEachIsAProbability() {
        when(repository.save(any(ShopOdds.class))).thenAnswer(call -> call.getArgument(0));
        assertThat(service.save(new ShopOdds("other", 0.1, 0.01, 0.001, 1, 0.5)).getId()).isEqualTo(ShopOdds.ID);

        assertThatThrownBy(() -> service.save(new ShopOdds(null, 1.2, 0, 0, 1, 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.save(new ShopOdds(null, 0.1, 0, -0.01, 1, 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.save(new ShopOdds(null, 0.6, 0.5, 0, 1, 1))).hasMessageContaining("100 %");
        assertThatThrownBy(() -> service.save(new ShopOdds(null, Double.NaN, 0, 0, 1, 1))).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(new ShopOdds(ShopOdds.ID, 0.6, 0.5, 0, 1, 1));
    }
}
