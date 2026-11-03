package com.drive.drive_manager.simulator.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The programmed cards are the files in resources/cards. */
class CardFilesTest {

    @Test
    void everyFileIsAValidCard() {
        List<CardDefinition> cards = CardFiles.load();
        assertThat(cards).hasSizeGreaterThanOrEqualTo(358);
        assertThat(cards).extracting(CardDefinition::name).doesNotHaveDuplicates();
    }

    @Test
    void theEngineRunsWhatTheFilesSay() {
        CardDefinition falis = CardFiles.load().stream().filter(c -> c.name().equals("falis")).findFirst().orElseThrow();
        assertThat(CardEffects.of("falis")).isEqualTo(falis.effects());
        // An Integer's 0 is not "not asked": "si reduces a 0 la fuerza"
        assertThat(EffectJson.write(falis)).contains("\"toAtMost\":0");
    }

    @Test
    void aCardFileLeavesOutWhatIsDefault() {
        CardDefinition damon = CardFiles.load().stream().filter(c -> c.name().equals("damon")).findFirst().orElseThrow();
        assertThat(EffectJson.pretty(damon)).doesNotContain("false").doesNotContain("[ ]");
    }
}
