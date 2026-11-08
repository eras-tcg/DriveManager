package com.drive.drive_manager.simulator.platform

import kotlin.random.Random
import kotlin.reflect.KClass

/**
 * JavaScript's platform: one thread (no locks, atomics or thread-local values needed), the browser's or Node's clocks,
 * `Math.random` (through `kotlin.random.Random.Default`) for what nobody seeded.
 */
actual object Platform {

    actual fun randomSeed(): Long = Random.Default.nextLong()

    /** No service loader here: what the core needs (the cards, the bot's resources) is installed by hand. */
    actual fun <T : Any> discover(type: KClass<T>): List<T> = emptyList()

    /** `Date.now()`. */
    actual fun currentTimeMillis(): Long = kotlin.js.Date.now().toLong()

    /** `performance.now()` (in milliseconds, with fractions) in nanoseconds; `Date.now()` where there is none. */
    actual fun nanoTime(): Long {
        val millis: Double = js("(typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now()")
        return (millis * 1_000_000.0).toLong()
    }

    actual fun now(): Instant = Instant(currentTimeMillis())

    actual fun seconds(seconds: Long): Duration = Duration(seconds)

    /** The Java pattern translated ([JavaRegex.toJavaScript]): Kotlin's `Regex` is a JavaScript `RegExp` here. */
    actual fun regex(pattern: String): Regex = Regex(JavaRegex.toJavaScript(pattern))

    /** A version-4 UUID's 32 hex digits (as `UUID.randomUUID()` without the dashes). */
    actual fun randomHexId(): String {
        val digits = "0123456789abcdef"
        return buildString(32) {
            for (i in 0 until 32) {
                append(
                    when (i) {
                        12 -> '4' // the version
                        16 -> digits[8 + Random.Default.nextInt(4)] // the variant: 8, 9, a or b
                        else -> digits[Random.Default.nextInt(16)]
                    }
                )
            }
        }
    }

    /** One thread: a plain map. */
    actual fun <K : Any, V : Any> concurrentMap(): MutableMap<K, V> = HashMap()

    /** A JavaScript `Map`, which tells objects apart by identity. */
    actual fun <K, V> identityMap(): MutableMap<K, V> = IdentityMap()

    /** No sorted collections here (`TreeSet`): never one to copy. */
    actual fun <T> emptySortedSetLike(o: Collection<T>): MutableSet<T>? = null

    /** No sorted maps here (`TreeMap`): never one to copy. */
    actual fun <K, V> emptySortedMapLike(o: Map<*, *>): MutableMap<K, V>? = null
}

/** One thread: Kotlin's `getOrPut` (the value is never null). */
actual fun <K, V : Any> MutableMap<K, V>.computeIfAbsentAtomic(key: K, compute: (K) -> V): V =
    get(key) ?: compute(key).also { put(key, it) }

/** One thread: nothing to lock. */
actual inline fun <R> withLock(lock: Any, block: () -> R): R = block()

/** Nothing skips fields here: a plain marker. */
@Target(AnnotationTarget.FIELD)
@MustBeDocumented
actual annotation class Transient actual constructor()

/** A point in time, in milliseconds since the epoch. */
actual class Instant(val epochMillis: Long) {

    override fun equals(other: Any?): Boolean = other is Instant && other.epochMillis == epochMillis

    override fun hashCode(): Int = epochMillis.hashCode()

    override fun toString(): String = kotlin.js.Date(epochMillis.toDouble()).toISOString()
}

/** A span of time, in seconds. */
actual class Duration(val seconds: Long) {

    override fun equals(other: Any?): Boolean = other is Duration && other.seconds == seconds

    override fun hashCode(): Int = seconds.hashCode()

    override fun toString(): String = "PT${seconds}S"
}

/** One thread: a plain holder, starting as `initial()` (or null) on first read. */
actual class LocalValue<T> actual constructor(private val initial: (() -> T)?) {

    private var value: T? = null
    private var present = false

    actual fun get(): T {
        if (!present) {
            value = initial?.invoke()
            present = true
        }
        return value.unsafeCast<T>()
    }

    actual fun set(value: T) {
        this.value = value
        present = true
    }

    actual fun remove() {
        value = null
        present = false
    }
}

/** One thread: a plain counter; reads as its value. */
actual class AtomicCounter actual constructor() {

    private var value = 0L

    actual fun get(): Long = value

    actual fun incrementAndGet(): Long = ++value

    override fun toString(): String = value.toString()
}

/** One thread: a plain flag. */
actual class AtomicFlag actual constructor() {

    private var value = false

    actual fun get(): Boolean = value

    actual fun set(newValue: Boolean) {
        value = newValue
    }

    actual fun compareAndSet(expect: Boolean, update: Boolean): Boolean {
        if (value != expect) return false
        value = update
        return true
    }
}

/**
 * A map whose keys are told apart by identity (`IdentityHashMap`'s contract), on a JavaScript `Map` (SameValueZero:
 * objects by identity; strings and numbers, which have no identity there, by value). Iterates in insertion order.
 */
internal class IdentityMap<K, V> : AbstractMutableMap<K, V>() {

    private val map: dynamic = js("new Map()")

    override val size: Int get() = map.size.unsafeCast<Int>()

    override fun containsKey(key: K): Boolean = map.has(key).unsafeCast<Boolean>()

    override fun get(key: K): V? = if (containsKey(key)) map.get(key).unsafeCast<V>() else null

    override fun put(key: K, value: V): V? {
        val old = get(key)
        map.set(key, value)
        return old
    }

    override fun remove(key: K): V? {
        val old = get(key)
        map.delete(key)
        return old
    }

    override fun clear() {
        map.clear()
    }

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = Entries()

    private inner class Entries : AbstractMutableSet<MutableMap.MutableEntry<K, V>>() {

        override val size: Int get() = this@IdentityMap.size

        override fun add(element: MutableMap.MutableEntry<K, V>): Boolean = throw UnsupportedOperationException()

        override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> {
            // A snapshot of the keys: removing through the iterator does not disturb it
            val keys: Array<K> = js("Array").from(map.keys()).unsafeCast<Array<K>>()
            return object : MutableIterator<MutableMap.MutableEntry<K, V>> {
                private var next = 0
                private var last = -1

                override fun hasNext(): Boolean = next < keys.size

                override fun next(): MutableMap.MutableEntry<K, V> {
                    if (!hasNext()) throw NoSuchElementException()
                    last = next++
                    return Entry(keys[last])
                }

                override fun remove() {
                    check(last >= 0) { "next() first" }
                    map.delete(keys[last])
                    last = -1
                }
            }
        }
    }

    private inner class Entry(override val key: K) : MutableMap.MutableEntry<K, V> {

        override val value: V get() = map.get(key).unsafeCast<V>()

        override fun setValue(newValue: V): V {
            val old = value
            map.set(key, newValue)
            return old
        }

        override fun equals(other: Any?): Boolean =
            other is Map.Entry<*, *> && other.key === key && other.value == value

        override fun hashCode(): Int = key.hashCode() xor value.hashCode()

        override fun toString(): String = "$key=$value"
    }
}
