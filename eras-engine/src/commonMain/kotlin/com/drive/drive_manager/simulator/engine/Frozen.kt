package com.drive.drive_manager.simulator.engine

/*
 * Java's unmodifiable collections (`List.of`, `List.copyOf`, `Set.of`, `Map.of`, `Map.ofEntries`) in plain Kotlin, with
 * their semantics, so the engine keeps them without `java.util`:
 * - a null element, key or value throws NullPointerException when the collection is made, a repeated element of a set or
 *   key of a map throws IllegalArgumentException;
 * - asking for null (`contains(null)`, `indexOf(null)`, `get(null)`, `containsKey(null)`...) throws NullPointerException;
 * - every change (`add`, `remove`, `clear`, `put`, through an iterator or an entry too) throws
 *   UnsupportedOperationException;
 * - equality, hash codes and `toString` are those of any list, set or map.
 * The one difference: Java's `Set.of` and `Map.of` iterate in an order that changes from one run of the JVM to the next;
 * these keep the order they were given (nothing in the engine reads that order, or it would have changed between runs).
 * Returned as the mutable types, as Java's were seen from Kotlin, so a caller passes them where Java took a `List`/`Map`.
 */

private fun <T> nonNull(value: T?): T = value ?: throw NullPointerException()

/** As `java.util.List.of`. */
fun <E> frozenListOf(vararg elements: E): MutableList<E> = FrozenList.of(arrayOf<Any?>(*elements))

/** As `java.util.List.copyOf`: an unmodifiable copy, refusing nulls. */
fun <E> frozenListCopy(elements: Collection<E>): MutableList<E> =
    if (elements is FrozenList<*>) elements as MutableList<E> else FrozenList.of(elements.toTypedArray<Any?>())

/** As `java.util.Set.of`. */
fun <E> frozenSetOf(vararg elements: E): MutableSet<E> {
    val items = LinkedHashSet<E>()
    for (e in elements) if (!items.add(nonNull(e))) throw IllegalArgumentException("duplicate element: $e")
    return FrozenSet(items)
}

/** As `java.util.Set.copyOf` (`Collectors.toUnmodifiableSet`): an unmodifiable copy, refusing nulls, repeats dropped. */
fun <E> frozenSetCopy(elements: Collection<E>): MutableSet<E> {
    if (elements is FrozenSet<*>) return elements as MutableSet<E>
    val items = LinkedHashSet<E>()
    for (e in elements) items.add(nonNull(e))
    return FrozenSet(items)
}

/** As `java.util.Map.ofEntries`. */
fun <K, V> frozenMapOfEntries(vararg entries: Pair<K, V>): MutableMap<K, V> {
    val kv = arrayOfNulls<Any?>(entries.size * 2)
    for (i in entries.indices) {
        kv[2 * i] = nonNull(entries[i]).first
        kv[2 * i + 1] = entries[i].second
    }
    return FrozenMap.of(kv)
}

/** As `java.util.Map.of` with 0 entries. */
fun <K, V> frozenMapOf(): MutableMap<K, V> = FrozenMap.of(arrayOf())

/** As `java.util.Map.of` with 1 entry. */
fun <K, V> frozenMapOf(k1: K, v1: V): MutableMap<K, V> = FrozenMap.of(arrayOf<Any?>(k1, v1))

/** As `java.util.Map.of` with 2 entries. */
fun <K, V> frozenMapOf(k1: K, v1: V, k2: K, v2: V): MutableMap<K, V> = FrozenMap.of(arrayOf<Any?>(k1, v1, k2, v2))

/** As `java.util.Map.of` with 3 entries. */
fun <K, V> frozenMapOf(k1: K, v1: V, k2: K, v2: V, k3: K, v3: V): MutableMap<K, V> = FrozenMap.of(arrayOf<Any?>(k1, v1, k2, v2, k3, v3))

/** As `java.util.Map.of` with 4 entries. */
fun <K, V> frozenMapOf(k1: K, v1: V, k2: K, v2: V, k3: K, v3: V, k4: K, v4: V): MutableMap<K, V> = FrozenMap.of(arrayOf<Any?>(k1, v1, k2, v2, k3, v3, k4, v4))

/** As `java.util.Map.of` with 5 entries. */
fun <K, V> frozenMapOf(k1: K, v1: V, k2: K, v2: V, k3: K, v3: V, k4: K, v4: V, k5: K, v5: V): MutableMap<K, V> = FrozenMap.of(arrayOf<Any?>(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5))

/** As `java.util.Map.of` with 6 entries. */
fun <K, V> frozenMapOf(k1: K, v1: V, k2: K, v2: V, k3: K, v3: V, k4: K, v4: V, k5: K, v5: V, k6: K, v6: V): MutableMap<K, V> = FrozenMap.of(arrayOf<Any?>(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6))

/** As `java.util.Map.of` with 7 entries. */
fun <K, V> frozenMapOf(k1: K, v1: V, k2: K, v2: V, k3: K, v3: V, k4: K, v4: V, k5: K, v5: V, k6: K, v6: V, k7: K, v7: V): MutableMap<K, V> = FrozenMap.of(arrayOf<Any?>(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7))

/** As `java.util.Map.of` with 8 entries. */
fun <K, V> frozenMapOf(k1: K, v1: V, k2: K, v2: V, k3: K, v3: V, k4: K, v4: V, k5: K, v5: V, k6: K, v6: V, k7: K, v7: V, k8: K, v8: V): MutableMap<K, V> = FrozenMap.of(arrayOf<Any?>(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8))

/** As `java.util.Map.of` with 9 entries. */
fun <K, V> frozenMapOf(k1: K, v1: V, k2: K, v2: V, k3: K, v3: V, k4: K, v4: V, k5: K, v5: V, k6: K, v6: V, k7: K, v7: V, k8: K, v8: V, k9: K, v9: V): MutableMap<K, V> = FrozenMap.of(arrayOf<Any?>(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9))

/** As `java.util.Map.of` with 10 entries. */
fun <K, V> frozenMapOf(k1: K, v1: V, k2: K, v2: V, k3: K, v3: V, k4: K, v4: V, k5: K, v5: V, k6: K, v6: V, k7: K, v7: V, k8: K, v8: V, k9: K, v9: V, k10: K, v10: V): MutableMap<K, V> = FrozenMap.of(arrayOf<Any?>(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10))

/** An unmodifiable list (see the file's comment). */
class FrozenList<E> private constructor(private val items: Array<Any?>) : AbstractMutableList<E>(), RandomAccess {

    internal companion object {
        fun <E> of(items: Array<Any?>): FrozenList<E> {
            for (e in items) nonNull(e)
            return FrozenList(items)
        }
    }

    override val size: Int get() = items.size

    @Suppress("UNCHECKED_CAST")
    override fun get(index: Int): E {
        if (index < 0 || index >= items.size) throw IndexOutOfBoundsException("Index $index out of bounds for length ${items.size}")
        return items[index] as E
    }

    override fun indexOf(element: E): Int {
        nonNull(element)
        for (i in items.indices) if (element == items[i]) return i
        return -1
    }

    override fun lastIndexOf(element: E): Int {
        nonNull(element)
        for (i in items.indices.reversed()) if (element == items[i]) return i
        return -1
    }

    override fun contains(element: E): Boolean = indexOf(element) >= 0

    @Suppress("UNCHECKED_CAST")
    override fun iterator(): MutableIterator<E> = FrozenIterator((items as Array<E>).iterator())

    override fun set(index: Int, element: E): E = throw UnsupportedOperationException()
    override fun add(index: Int, element: E): Unit = throw UnsupportedOperationException()
    override fun removeAt(index: Int): E = throw UnsupportedOperationException()
    override fun add(element: E): Boolean = throw UnsupportedOperationException()
    override fun addAll(elements: Collection<E>): Boolean = throw UnsupportedOperationException()
    override fun addAll(index: Int, elements: Collection<E>): Boolean = throw UnsupportedOperationException()
    override fun remove(element: E): Boolean = throw UnsupportedOperationException()
    override fun removeAll(elements: Collection<E>): Boolean = throw UnsupportedOperationException()
    override fun retainAll(elements: Collection<E>): Boolean = throw UnsupportedOperationException()
    override fun clear(): Unit = throw UnsupportedOperationException()
}

/** An unmodifiable set (see the file's comment), in the order it was given. */
class FrozenSet<E> internal constructor(private val items: Set<E>) : AbstractMutableSet<E>() {

    override val size: Int get() = items.size

    override fun contains(element: E): Boolean = items.contains(nonNull(element))

    override fun iterator(): MutableIterator<E> = FrozenIterator(items.iterator())

    override fun add(element: E): Boolean = throw UnsupportedOperationException()
    override fun addAll(elements: Collection<E>): Boolean = throw UnsupportedOperationException()
    override fun remove(element: E): Boolean = throw UnsupportedOperationException()
    override fun removeAll(elements: Collection<E>): Boolean = throw UnsupportedOperationException()
    override fun retainAll(elements: Collection<E>): Boolean = throw UnsupportedOperationException()
    override fun clear(): Unit = throw UnsupportedOperationException()
}

/** An unmodifiable map (see the file's comment), in the order it was given. */
class FrozenMap<K, V> private constructor(private val map: Map<K, V>) : AbstractMutableMap<K, V>() {

    internal companion object {
        /** From keys and values one after the other (`k1, v1, k2, v2...`), as `Map.of`. */
        @Suppress("UNCHECKED_CAST")
        fun <K, V> of(kv: Array<Any?>): FrozenMap<K, V> {
            val map = LinkedHashMap<K, V>()
            var i = 0
            while (i < kv.size) {
                val k = nonNull(kv[i]) as K
                val v = nonNull(kv[i + 1]) as V
                if (map.containsKey(k)) throw IllegalArgumentException("duplicate key: $k")
                map[k] = v
                i += 2
            }
            return FrozenMap(map)
        }
    }

    private val entrySet: MutableSet<MutableMap.MutableEntry<K, V>> by lazy {
        val all = LinkedHashSet<MutableMap.MutableEntry<K, V>>()
        for (e in map.entries) all.add(FrozenEntry(e.key, e.value))
        FrozenSet(all)
    }

    override val size: Int get() = map.size

    override fun get(key: K): V? = map[nonNull(key)]

    override fun containsKey(key: K): Boolean = map.containsKey(nonNull(key))

    override fun containsValue(value: V): Boolean = map.containsValue(nonNull(value))

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() = entrySet

    override fun put(key: K, value: V): V? = throw UnsupportedOperationException()
    override fun putAll(from: Map<out K, V>): Unit = throw UnsupportedOperationException()
    override fun remove(key: K): V? = throw UnsupportedOperationException()
    override fun clear(): Unit = throw UnsupportedOperationException()

}

private class FrozenIterator<E>(private val inner: Iterator<E>) : MutableIterator<E> {
    override fun hasNext(): Boolean = inner.hasNext()
    override fun next(): E = inner.next()
    override fun remove(): Unit = throw UnsupportedOperationException()
}

private class FrozenEntry<K, V>(override val key: K, override val value: V) : MutableMap.MutableEntry<K, V> {
    override fun setValue(newValue: V): V = throw UnsupportedOperationException()
    override fun equals(other: Any?): Boolean = other is Map.Entry<*, *> && key == other.key && value == other.value
    override fun hashCode(): Int = (key?.hashCode() ?: 0) xor (value?.hashCode() ?: 0)
    override fun toString(): String = "$key=$value"
}

/**
 * As `Collections.unmodifiableMap(map)`: a read-only view of `map`, which reads as the map does (nulls included) and
 * refuses every change with UnsupportedOperationException.
 */
fun <K, V> unmodifiableMapOf(map: Map<K, V>): MutableMap<K, V> = UnmodifiableMap(map)

private class UnmodifiableMap<K, V>(private val inner: Map<K, V>) : AbstractMutableMap<K, V>() {
    override val size: Int get() = inner.size
    override fun get(key: K): V? = inner[key]
    override fun containsKey(key: K): Boolean = inner.containsKey(key)
    override fun containsValue(value: V): Boolean = inner.containsValue(value)
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() = Entries(inner.entries)
    override fun put(key: K, value: V): V? = throw UnsupportedOperationException()
    override fun putAll(from: Map<out K, V>): Unit = throw UnsupportedOperationException()
    override fun remove(key: K): V? = throw UnsupportedOperationException()
    override fun clear(): Unit = throw UnsupportedOperationException()

    private class Entries<K, V>(private val inner: Set<Map.Entry<K, V>>) : AbstractMutableSet<MutableMap.MutableEntry<K, V>>() {
        override val size: Int get() = inner.size
        override fun contains(element: MutableMap.MutableEntry<K, V>): Boolean = inner.contains(element)
        override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> {
            val i = inner.iterator()
            return object : MutableIterator<MutableMap.MutableEntry<K, V>> {
                override fun hasNext(): Boolean = i.hasNext()
                override fun next(): MutableMap.MutableEntry<K, V> = i.next().let { FrozenEntry(it.key, it.value) }
                override fun remove(): Unit = throw UnsupportedOperationException()
            }
        }
        override fun add(element: MutableMap.MutableEntry<K, V>): Boolean = throw UnsupportedOperationException()
        override fun addAll(elements: Collection<MutableMap.MutableEntry<K, V>>): Boolean = throw UnsupportedOperationException()
        override fun remove(element: MutableMap.MutableEntry<K, V>): Boolean = throw UnsupportedOperationException()
        override fun removeAll(elements: Collection<MutableMap.MutableEntry<K, V>>): Boolean = throw UnsupportedOperationException()
        override fun retainAll(elements: Collection<MutableMap.MutableEntry<K, V>>): Boolean = throw UnsupportedOperationException()
        override fun clear(): Unit = throw UnsupportedOperationException()
    }
}
