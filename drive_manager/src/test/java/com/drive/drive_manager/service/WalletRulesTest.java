package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.EditionMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WalletRulesTest {

    @Test
    void aNewWalletStartsWithTheStartingGemsAndNoExtraReward() {
        WalletRules.Opened opened = WalletRules.open(null, "2026-11-02");
        assertThat(opened.state()).isEqualTo(new WalletRules.State(WalletRules.STARTING_GEMS, "2026-11-02"));
        assertThat(opened.reward()).isZero();
    }

    @Test
    void theDailyRewardComesOncePerDay() {
        WalletRules.Opened first = WalletRules.open(new WalletRules.State(50, "2026-11-01"), "2026-11-02");
        assertThat(first.state().gems()).isEqualTo(50 + WalletRules.DAILY_GEMS);
        assertThat(first.reward()).isEqualTo(WalletRules.DAILY_GEMS);
        assertThat(WalletRules.open(first.state(), "2026-11-02").reward()).isZero();
    }

    @Test
    void packsCostTheSameAndBoxesDependOnTheEdition() {
        assertThat(WalletRules.priceOf(edition("E1", true), "pack")).isEqualTo(WalletRules.PACK_PRICE);
        assertThat(WalletRules.priceOf(edition("E1", true), "box")).isEqualTo(WalletRules.BOX_PRICE);
        assertThat(WalletRules.priceOf(edition("E1.2", true), "box")).isEqualTo(WalletRules.SUB_EDITION_BOX_PRICE);
    }

    @Test
    void whatTheEditionDoesNotSellHasNoPrice() {
        assertThat(WalletRules.priceOf(edition("E1", false), "box")).isNull();
        assertThat(WalletRules.priceOf(edition("E1", true), "deluxe")).isNull();
        EditionMetadata noPacks = edition("ST1", false);
        noPacks.setPackTypes(List.of());
        assertThat(WalletRules.priceOf(noPacks, "pack")).isNull();
        assertThat(WalletRules.priceOf(null, "pack")).isNull();
    }

    private static EditionMetadata edition(String id, boolean withBox) {
        EditionMetadata e = new EditionMetadata();
        e.setEditionId(id);
        e.setPackTypes(List.of(new EditionMetadata.PackTypeConfig("Normal", Map.of("C", 7, "UC", 3, "R", 1))));
        if (withBox) {
            EditionMetadata.BoxEntry entry = new EditionMetadata.BoxEntry();
            entry.setPackTypeName("Normal");
            entry.setMode("FLEX");
            e.setBoxConfig(new EditionMetadata.BoxConfig(24, List.of(entry)));
        }
        return e;
    }
}
