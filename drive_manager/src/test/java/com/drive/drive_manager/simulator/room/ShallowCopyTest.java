package com.drive.drive_manager.simulator.room;

import com.drive.drive_manager.simulator.engine.GameEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ShallowCopy copies field by field what BeanUtils.copyProperties copied by reflection: every field of these classes,
 * filled with a value of its own, comes out the same both ways (a field added and not copied fails here). One field
 * differs on purpose: a card's {@code level}. BeanUtils read it through {@code getLevel()}, which adds the continuous
 * level, and copied the continuous level as well, so the copy counted that bonus twice (the rival's view of a face-up
 * card with face-down materials, StateMask). ShallowCopy copies the field itself: the oracle is BeanUtils with that
 * field corrected, and a copy's {@code getLevel()} is the original's.
 */
class ShallowCopyTest {

    @Test
    void copiesAsBeanUtils() throws Exception {
        check(GameStateUpdate::new, ShallowCopy::of, (original, expected) -> { });
        check(() -> new PlayerState("u", "name"), ShallowCopy::of, (original, expected) -> { });
        // BeanUtils took getLevel() (printed + continuous) for the level: the copy keeps the printed one
        check(CardSlot::new, ShallowCopy::of, (original, expected) -> expected.setLevel(original.getPrintedLevel()));
        check(GameEvent::new, ShallowCopy::of, (original, expected) -> { });
        check(GameLogEntry::new, ShallowCopy::of, (original, expected) -> { });
    }

    @Test
    void aCardsCopyHasItsLevelWithTheContinuousBonusCountedOnce() {
        CardSlot card = new CardSlot();
        card.setLevel(2);
        card.setContinuousLevel(3);
        CardSlot copy = ShallowCopy.of(card);
        assertThat(copy.getPrintedLevel()).isEqualTo(2);
        assertThat(copy.getContinuousLevel()).isEqualTo(3);
        assertThat(copy.getLevel()).isEqualTo(card.getLevel()).isEqualTo(5);
    }

    private static <T> void check(Supplier<T> make, UnaryOperator<T> copy, BiConsumer<T, T> corrected) throws Exception {
        T original = make.get();
        int n = 0;
        for (Field f : fields(original.getClass())) f.set(original, valueFor(f, ++n));
        T expected = make.get();
        BeanUtils.copyProperties(original, expected);
        corrected.accept(original, expected);
        T actual = copy.apply(original);
        for (Field f : fields(original.getClass())) {
            assertThat(f.get(actual)).as(original.getClass().getSimpleName() + "." + f.getName()).isEqualTo(f.get(expected));
        }
    }

    private static Object valueFor(Field f, int n) {
        Class<?> t = f.getType();
        if (t == boolean.class || t == Boolean.class) return n % 2 == 0;
        if (t == int.class || t == Integer.class) return n;
        if (t == long.class || t == Long.class) return (long) n * 1000;
        if (t == double.class || t == Double.class) return n + 0.5;
        if (t == String.class) return "v" + n;
        if (List.class.isAssignableFrom(t)) return new ArrayList<>(List.of());
        if (java.util.Map.class.isAssignableFrom(t)) return new HashMap<>();
        if (t.isEnum()) return t.getEnumConstants()[n % t.getEnumConstants().length];
        return null;
    }

    private static List<Field> fields(Class<?> type) {
        List<Field> all = new ArrayList<>();
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            all.add(f);
        }
        return all;
    }
}
