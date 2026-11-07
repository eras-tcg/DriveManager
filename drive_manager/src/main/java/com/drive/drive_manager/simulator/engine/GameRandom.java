package com.drive.drive_manager.simulator.engine;

import java.util.List;
import java.util.Random;

/**
 * The game's shuffles in one place: exactly {@code Collections.shuffle(list, random)} (from the last position down,
 * each element swaps with one at or before it), written out so the engine in Kotlin, which has no
 * {@code java.util.Collections}, can do the same shuffle from the same seed (see eras-engine's GameRandom). A replay
 * kept by the server plays out the same anywhere only if every shuffle draws the same numbers.
 */
public final class GameRandom {

    private GameRandom() {}

    public static <T> void shuffle(List<T> list, Random random) {
        for (int i = list.size(); i > 1; i--) {
            int j = random.nextInt(i);
            list.set(i - 1, list.set(j, list.get(i - 1)));
        }
    }
}
