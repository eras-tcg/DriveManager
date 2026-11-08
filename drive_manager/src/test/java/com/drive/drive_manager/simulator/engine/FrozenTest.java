package com.drive.drive_manager.simulator.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The frozen collections behave as Java's List.of, List.copyOf, Set.of, Map.of and Map.ofEntries. */
class FrozenTest {

    @Test
    void listsAsListOf() {
        List<String> frozen = FrozenKt.frozenListOf("a", "b", "a");
        List<String> java = List.of("a", "b", "a");
        assertThat(frozen).isEqualTo(java).hasSameHashCodeAs(java);
        assertThat(frozen.toString()).isEqualTo(java.toString());
        assertThat(frozen.indexOf("a")).isEqualTo(0);
        assertThat(frozen.lastIndexOf("a")).isEqualTo(2);
        assertThat(frozen.subList(1, 3)).isEqualTo(java.subList(1, 3));
        assertThatThrownBy(() -> frozen.contains(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> java.contains(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> frozen.add("c")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> frozen.set(0, "c")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> frozen.remove(0)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(frozen::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> frozen.iterator().remove()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> frozen.get(3)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> FrozenKt.frozenListOf("a", null)).isInstanceOf(NullPointerException.class);
        assertThat(FrozenKt.<String>frozenListOf()).isEmpty();

        List<String> source = new ArrayList<>(List.of("x", "y"));
        List<String> copy = FrozenKt.frozenListCopy(source);
        source.add("z");
        assertThat(copy).containsExactly("x", "y");
        assertThat(FrozenKt.frozenListCopy(copy)).isSameAs(copy);
        source.add(null);
        assertThatThrownBy(() -> FrozenKt.frozenListCopy(source)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void setsAsSetOf() {
        Set<String> frozen = FrozenKt.frozenSetOf("a", "b", "c");
        Set<String> java = Set.of("a", "b", "c");
        assertThat(frozen).isEqualTo(java).hasSameHashCodeAs(java);
        assertThat(frozen).containsExactly("a", "b", "c"); // in the order given
        assertThatThrownBy(() -> frozen.contains(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> java.contains(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> frozen.add("d")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> frozen.remove("a")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> FrozenKt.frozenSetOf("a", "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Set.of("a", "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FrozenKt.frozenSetOf("a", null)).isInstanceOf(NullPointerException.class);
        // As Set.copyOf: repeats dropped
        assertThat(FrozenKt.frozenSetCopy(List.of("a", "a", "b"))).isEqualTo(new HashSet<>(List.of("a", "b")));
    }

    @Test
    void mapsAsMapOf() {
        Map<String, Object> frozen = FrozenKt.frozenMapOf("k", 1, "j", "v");
        Map<String, Object> java = Map.of("k", 1, "j", "v");
        assertThat(frozen).isEqualTo(java).hasSameHashCodeAs(java);
        assertThat(frozen.entrySet()).isEqualTo(java.entrySet());
        assertThat(frozen.toString()).isEqualTo("{k=1, j=v}"); // in the order given
        assertThat(frozen.get("k")).isEqualTo(1);
        assertThat(frozen.get("missing")).isNull();
        assertThatThrownBy(() -> frozen.get(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> java.get(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> frozen.containsKey(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> frozen.containsValue(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> frozen.put("x", 2)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> frozen.remove("k")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> frozen.entrySet().iterator().next().setValue(3)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> FrozenKt.frozenMapOf("k", 1, "k", 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FrozenKt.frozenMapOf("k", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> FrozenKt.frozenMapOf(null, 1)).isInstanceOf(NullPointerException.class);
        assertThat(new HashMap<>(frozen)).isEqualTo(java);
    }

    @Test
    void unmodifiableMapReadsThrough() {
        Map<String, Double> inner = new HashMap<>();
        inner.put("a", 1.0);
        inner.put("n", null);
        Map<String, Double> view = FrozenKt.unmodifiableMapOf(inner);
        assertThat(view).isEqualTo(inner);
        assertThat(view.get("n")).isNull();
        assertThat(view.containsKey("n")).isTrue();
        assertThatThrownBy(() -> view.put("b", 2.0)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.entrySet().iterator().next().setValue(3.0)).isInstanceOf(UnsupportedOperationException.class);
    }
}
