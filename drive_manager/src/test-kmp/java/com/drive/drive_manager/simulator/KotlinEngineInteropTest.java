package com.drive.drive_manager.simulator;

import com.eras.engine.CardOutline;
import com.eras.engine.CardFileKt;
import com.eras.engine.EffectOutline;
import com.eras.engine.EngineApi;
import com.drive.drive_manager.simulator.engine.GameRandom;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Kotlin Multiplatform engine's artifact (eras-engine-jvm) used from DriveManager's Java, on real data. Runs only
 * with the kmp-engine profile (see pom.xml).
 */
class KotlinEngineInteropTest {

    /** A deck shuffled by the Kotlin engine comes out as the game shuffles it today (Collections.shuffle). */
    @Test
    void shufflesADeckAsTheGameDoes() {
        for (long seed : new long[] {0, 42, -7, 1794067701123L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            List<Integer> today = new ArrayList<>(IntStream.range(0, 40).boxed().toList());
            Collections.shuffle(today, new Random(seed));
            List<Integer> kotlin = new ArrayList<>(IntStream.range(0, 40).boxed().toList());
            GameRandom.shuffle(kotlin, new GameRandom(seed));
            assertThat(kotlin).as("seed %d", seed).isEqualTo(today);
        }
    }

    /** The first-player draw and the next game's seed (PreRoomActions) come out the same. */
    @Test
    void drawsAsTheRoomsRandom() {
        Random today = new Random(99);
        GameRandom kotlin = new GameRandom(99);
        for (int i = 0; i < 1000; i++) {
            assertThat(kotlin.nextLong()).isEqualTo(today.nextLong());
            assertThat(kotlin.nextInt(2)).isEqualTo(today.nextInt(2));
        }
    }

    /** Every card file reads the same with kotlinx.serialization (Kotlin) as with Jackson (today). */
    @Test
    void readsEveryCardFileAsJackson() throws Exception {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:cards/**/*.json");
        assertThat(files).hasSizeGreaterThan(250);
        ObjectMapper jackson = new ObjectMapper();
        for (Resource file : files) {
            String text = file.getContentAsString(StandardCharsets.UTF_8);
            JsonNode node = jackson.readTree(text);
            CardOutline card = CardFileKt.readCardOutline(text);
            assertThat(card.getName()).as(file.getFilename()).isEqualTo(node.get("name").asText());
            List<String> ids = new ArrayList<>();
            node.path("effects").forEach(e -> ids.add(e.get("id").asText()));
            assertThat(card.getEffects().stream().map(EffectOutline::getId).toList()).as(file.getFilename()).isEqualTo(ids);
        }
    }

    /** The facade the site calls answers from Java too. */
    @Test
    void facadeAnswers() {
        assertThat(EngineApi.INSTANCE.shuffledOrder("42", 5)).hasSize(5);
    }
}
