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
        assertThat(WalletRules.priceOf(edition("E1", true), "pack", List.of())).isEqualTo(WalletRules.PACK_PRICE);
        assertThat(WalletRules.priceOf(edition("E1", true), "box", List.of())).isEqualTo(WalletRules.BOX_PRICE);
        assertThat(WalletRules.priceOf(edition("E1.2", true), "box", List.of())).isEqualTo(WalletRules.SUB_EDITION_BOX_PRICE);
    }

    @Test
    void aStructureDeckSellsItselfWhole() {
        EditionMetadata structure = edition("ST1", false);
        structure.setPackTypes(null);
        assertThat(WalletRules.priceOf(structure, "structure", List.of())).isZero();
        assertThat(WalletRules.priceOf(structure, "pack", List.of())).isNull();
        assertThat(WalletRules.priceOf(edition("E1", true), "structure", List.of())).isNull();
    }

    @Test
    void theFirstOfST1ToST4IsFreeTheOthersCostTheStarterPriceAndTheRestTheUsualPrice() {
        EditionMetadata st1 = edition("ST1", false);
        EditionMetadata st5 = edition("ST5", false);
        assertThat(WalletRules.priceOf(st1, "structure", List.of())).isZero();
        assertThat(WalletRules.priceOf(st1, "structure", List.of("ST5"))).isZero();
        assertThat(WalletRules.priceOf(st1, "structure", List.of("ST3"))).isEqualTo(WalletRules.STARTER_PRICE);
        assertThat(WalletRules.priceOf(st5, "structure", List.of())).isEqualTo(WalletRules.STRUCTURE_PRICE);
        assertThat(WalletRules.priceOf(st5, "structure", List.of("ST1"))).isEqualTo(WalletRules.STRUCTURE_PRICE);
        assertThat(WalletRules.ownsStarter(List.of("st2"))).isTrue();
        assertThat(WalletRules.ownsStarter(List.of("ST5", "ST8"))).isFalse();
    }

    @Test
    void whatTheEditionDoesNotSellHasNoPrice() {
        assertThat(WalletRules.priceOf(edition("E1", false), "box", List.of())).isNull();
        assertThat(WalletRules.priceOf(edition("E1", true), "deluxe", List.of())).isNull();
        EditionMetadata noPacks = edition("ST1", false);
        noPacks.setPackTypes(List.of());
        assertThat(WalletRules.priceOf(noPacks, "pack", List.of())).isNull();
        assertThat(WalletRules.priceOf(null, "pack", List.of())).isNull();
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
