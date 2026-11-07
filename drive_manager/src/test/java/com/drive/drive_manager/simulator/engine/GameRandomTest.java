package com.drive.drive_manager.simulator.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** GameRandom.shuffle shuffles exactly as Collections.shuffle, on random-access lists and linked ones. */
class GameRandomTest {

    @Test
    void shufflesAsCollectionsShuffle() {
        for (long seed = -20; seed <= 20; seed++) for (int size : new int[] {0, 1, 2, 4, 5, 6, 40, 61}) {
            List<Integer> java = new ArrayList<>(IntStream.range(0, size).boxed().toList());
            Collections.shuffle(java, new Random(seed));
            List<Integer> ours = new ArrayList<>(IntStream.range(0, size).boxed().toList());
            GameRandom.shuffle(ours, new Random(seed));
            assertThat(ours).as("seed %d, size %d", seed, size).isEqualTo(java);
            List<Integer> linked = new LinkedList<>(IntStream.range(0, size).boxed().toList());
            GameRandom.shuffle(linked, new Random(seed));
            assertThat(linked).as("linked, seed %d, size %d", seed, size).isEqualTo(java);
        }
    }
}
