package com.drive.drive_manager.simulator.platform

import kotlin.jvm.JvmStatic
import kotlin.reflect.KClass

/**
 * What the game's core needs from the platform it runs on, in one place, with one `actual` per target: the JVM's
 * (jvmMain: exactly what the server ran before the core moved here) and JavaScript's (jsMain: one thread, so plain maps,
 * no locks, no atomics). Nothing else in the core may use a platform-only API.
 */
expect object Platform {

    /** A seed for a chance nobody seeded (`new java.util.Random()` on the JVM): different each time. */
    @JvmStatic
    fun randomSeed(): Long

    /**
     * The implementations of `type` the program offers as services, in the order found (`java.util.ServiceLoader` on the
     * JVM; none in JavaScript, where they are installed by hand): how the core finds what the server fills in for it (the
     * cards, the bot's resources) without depending on the server.
     */
    @JvmStatic
    fun <T : Any> discover(type: KClass<T>): List<T>

    /** The wall clock in milliseconds since the epoch: the log's and events' times. */
    @JvmStatic
    fun currentTimeMillis(): Long

    /** A monotonic clock in nanoseconds: the bot's time budgets. */
    @JvmStatic
    fun nanoTime(): Long

    /** Now, as an [Instant]. */
    @JvmStatic
    fun now(): Instant

    /** A span of `seconds` seconds. */
    @JvmStatic
    fun seconds(seconds: Long): Duration

    /** 32 random hex digits (a random UUID's, without the dashes). */
    @JvmStatic
    fun randomHexId(): String

    /** A map safe to share between threads (no null keys or values). */
    @JvmStatic
    fun <K : Any, V : Any> concurrentMap(): MutableMap<K, V>

    /** A map that tells its keys apart by identity, not by `equals`. */
    @JvmStatic
    fun <K, V> identityMap(): MutableMap<K, V>

    /**
     * An empty set sorted as `o` is, when `o` is a sorted set, else null: so a copy keeps the original's kind.
     */
    @JvmStatic
    fun <T> emptySortedSetLike(o: Collection<T>): MutableSet<T>?

    /** As [emptySortedSetLike], for maps. */
    @JvmStatic
    fun <K, V> emptySortedMapLike(o: Map<*, *>): MutableMap<K, V>?
}

/**
 * As `map.computeIfAbsent(key, compute)` of Java's maps: atomic on a [Platform.concurrentMap] (the value is computed
 * once per key even with threads racing), unlike Kotlin's `getOrPut`.
 */
expect fun <K, V : Any> MutableMap<K, V>.computeIfAbsentAtomic(key: K, compute: (K) -> V): V

/** Runs `block` holding `lock`'s monitor (`synchronized (lock) { … }` on the JVM; just runs it in JavaScript). */
expect inline fun <R> withLock(lock: Any, block: () -> R): R

/** A point in time (`java.time.Instant` on the JVM): the rooms' creation and emptiness times. */
expect class Instant

/** A span of time (`java.time.Duration` on the JVM): the rooms' limits. */
expect class Duration

/** Marks a field the JVM's serialization and Jackson skip (`@kotlin.jvm.Transient`; nothing elsewhere). */
@Target(AnnotationTarget.FIELD)
@MustBeDocumented
expect annotation class Transient()

/** A value of each thread its own (`java.lang.ThreadLocal` on the JVM), starting as `initial()` (or null without one). */
expect class LocalValue<T>(initial: (() -> T)? = null) {

    fun get(): T

    fun set(value: T)

    fun remove()
}

/** A counter safe to share between threads (`AtomicLong` on the JVM); reads as its value. */
expect class AtomicCounter() {

    fun get(): Long

    fun incrementAndGet(): Long
}

/** A flag safe to share between threads (`AtomicBoolean` on the JVM). */
expect class AtomicFlag() {

    fun get(): Boolean

    fun set(newValue: Boolean)

    fun compareAndSet(expect: Boolean, update: Boolean): Boolean
}
