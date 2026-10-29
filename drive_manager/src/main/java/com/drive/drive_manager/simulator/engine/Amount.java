package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.GameRoom;

/**
 * A number an effect reads from the game ("reduciendo su coste en X, donde X es …", "gana fuerza 1 por cada aliado
 * [legionario]"), as data: a closed vocabulary of records, like {@link Condition}.
 */
public sealed interface Amount {

    int of(GameRoom room, String controller);

    /** A fixed number. */
    record Fixed(int value) implements Amount {
        public int of(GameRoom room, String controller) {
            return value;
        }
    }

    /** How many of your cards on the field match. */
    record OnField(CardFilter filter) implements Amount {
        public int of(GameRoom room, String controller) {
            return (int) EffectEngine.countOnField(room, controller, filter);
        }
    }

    /** How many cards of one of your zones ("tributeZone", "hand") match. */
    record InZone(String zone, CardFilter filter) implements Amount {
        public int of(GameRoom room, String controller) {
            return (int) EffectEngine.countInZone(room, controller, zone, filter);
        }
    }

    /** The sum of the levels of your creatures on the field that match. */
    record LevelsOnField(CardFilter filter) implements Amount {
        public int of(GameRoom room, String controller) {
            return EffectEngine.levelsOnField(room, controller, filter);
        }
    }

    /** {@code base / divisor + plus} ("aumenta en 1 el nivel por cada 2 copias": 1 + copias / 2). */
    record Scaled(Amount base, int divisor, int plus) implements Amount {
        public int of(GameRoom room, String controller) {
            return base.of(room, controller) / divisor + plus;
        }
    }

    /** {@code base × factor} ("gana fuerza 3 por cada copia": copias × 3). */
    record Times(Amount base, int factor) implements Amount {
        public int of(GameRoom room, String controller) {
            return base.of(room, controller) * factor;
        }
    }

    /** One amount while a condition on the table holds, another otherwise ("si tienes 3 o menos vidas: …"). */
    record IfThen(Condition condition, Amount then, Amount otherwise) implements Amount {
        public int of(GameRoom room, String controller) {
            return condition.test(room, null, null, controller) ? then.of(room, controller) : otherwise.of(room, controller);
        }
    }

    static Amount fixed(int value) {
        return new Fixed(value);
    }
}
