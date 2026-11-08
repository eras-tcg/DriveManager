package com.drive.drive_manager.simulator.offline;

import com.drive.drive_manager.repository.CardRepository;
import com.drive.drive_manager.repository.DeckListRepository;
import com.drive.drive_manager.repository.DriveCardRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The endpoint and its services are made by Spring from what the application has (the missions and history optional). */
class OfflineGamesWiringTest {

    @Test
    void theEndpointIsWiredWithTheApplicationsDefaults() {
        new ApplicationContextRunner()
                .withBean(MongoTemplate.class, () -> mock(MongoTemplate.class))
                .withBean(DeckListRepository.class, () -> mock(DeckListRepository.class))
                .withBean(DriveCardRepository.class, () -> mock(DriveCardRepository.class))
                .withBean(CardRepository.class, () -> mock(CardRepository.class))
                .withUserConfiguration(OfflineDecks.class, MongoOfflineGameStore.class, OfflineGames.class, OfflineGameController.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(OfflineGameController.class);
                    OfflineGames.Rules rules = context.getBean(OfflineGames.class).rules();
                    assertThat(rules.dailyReplays()).isEqualTo(200);
                    assertThat(rules.windowDays()).isEqualTo(30);
                    assertThat(rules.tickNanos()).isEqualTo(1_000_000L);
                });
    }
}
