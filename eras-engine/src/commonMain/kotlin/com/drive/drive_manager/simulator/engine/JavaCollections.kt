package com.drive.drive_manager.simulator.engine

/*
 * The default methods of Java's collections (`Map.getOrDefault`, `Map.merge`, `List.replaceAll`) that Kotlin's common
 * collections lack, with Java's semantics. (`computeIfAbsent` is Kotlin's `getOrPut` when the value made is never
 * null, `removeIf` is `removeAll { }` when the test does not read the collection, `forEach { k, v -> }` is a loop over
 * the entries.)
 */

/** As `Map.getOrDefault`: the key's value when the map has the key (a null value too), else `default`. */
@Suppress("UNCHECKED_CAST")
fun <K, V> Map<out K, V>.valueOr(key: K, default: V): V {
    val v = get(key)
    return if (v != null || containsKey(key)) v as V else default
}

/**
 * As `Map.merge`: puts `value` when the key has none (or null), else the result of `remap` on the old value and `value`,
 * removing the key when that is null; gives what the key holds now.
 */
fun <K, V : Any> MutableMap<K, V>.mergeWith(key: K, value: V, remap: (V, V) -> V?): V? {
    val old = get(key)
    val now = if (old == null) value else remap(old, value)
    if (now == null) remove(key) else put(key, now)
    return now
}

/** As `List.replaceAll`: each element replaced, in order, by what `f` makes of it. */
inline fun <E> MutableList<E>.replaceEach(f: (E) -> E) {
    for (i in indices) this[i] = f(this[i])
}
