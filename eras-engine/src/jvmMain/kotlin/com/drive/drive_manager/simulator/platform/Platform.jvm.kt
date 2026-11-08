package com.drive.drive_manager.simulator.platform

import kotlin.reflect.KClass

/** The JVM's platform: what the server ran before the core moved to eras-engine, unchanged. */
actual object Platform {

    /** `new java.util.Random().nextLong()`. */
    @JvmStatic
    actual fun randomSeed(): Long = java.util.Random().nextLong()

    /** `java.util.ServiceLoader`, from the META-INF/services files of `type`'s class loader. */
    @JvmStatic
    actual fun <T : Any> discover(type: KClass<T>): List<T> =
        java.util.ServiceLoader.load(type.java, type.java.classLoader).toList()

    /** `System.currentTimeMillis()`. */
    @JvmStatic
    actual fun currentTimeMillis(): Long = System.currentTimeMillis()

    /** `System.nanoTime()`. */
    @JvmStatic
    actual fun nanoTime(): Long = System.nanoTime()

    /** `Instant.now()`. */
    @JvmStatic
    actual fun now(): Instant = Instant.now()

    /** `Duration.ofSeconds`. */
    @JvmStatic
    actual fun seconds(seconds: Long): Duration = Duration.ofSeconds(seconds)

    /** `UUID.randomUUID().toString().replace("-", "")`. */
    @JvmStatic
    actual fun randomHexId(): String = java.util.UUID.randomUUID().toString().replace("-", "")

    /** The pattern as it is (Kotlin's `Regex` is `java.util.regex.Pattern` on the JVM). */
    @JvmStatic
    actual fun regex(pattern: String): Regex = Regex(pattern)

    /** `java.util.HashMap`. */
    actual fun <K, V> hashMap(): MutableMap<K, V> = HashMap()

    actual fun <K, V> hashMap(from: Map<out K, V>): MutableMap<K, V> = HashMap(from)

    /** `Double.toString`. */
    actual fun doubleText(value: Double): String = value.toString()

    /** `ConcurrentHashMap`. */
    @JvmStatic
    actual fun <K : Any, V : Any> concurrentMap(): MutableMap<K, V> = java.util.concurrent.ConcurrentHashMap()

    /** `IdentityHashMap`. */
    @JvmStatic
    actual fun <K, V> identityMap(): MutableMap<K, V> = java.util.IdentityHashMap()

    /** `new TreeSet(o.comparator())` for a sorted set, else null. */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    actual fun <T> emptySortedSetLike(o: Collection<T>): MutableSet<T>? =
        if (o is java.util.SortedSet<*>) java.util.TreeSet(o.comparator() as Comparator<in T>?) else null

    /** `new TreeMap(o.comparator())` for a sorted map, else null. */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    actual fun <K, V> emptySortedMapLike(o: Map<*, *>): MutableMap<K, V>? =
        if (o is java.util.SortedMap<*, *>) java.util.TreeMap(o.comparator() as Comparator<in K>?) else null
}

/** Java's `computeIfAbsent` (atomic on a `ConcurrentHashMap`). */
actual fun <K, V : Any> MutableMap<K, V>.computeIfAbsentAtomic(key: K, compute: (K) -> V): V = computeIfAbsent(key, compute)

/** `synchronized (lock) { … }`. */
actual inline fun <R> withLock(lock: Any, block: () -> R): R = synchronized(lock, block)

actual typealias Instant = java.time.Instant

actual typealias Duration = java.time.Duration

actual typealias Transient = kotlin.jvm.Transient

/** `java.lang.ThreadLocal`. */
actual class LocalValue<T> actual constructor(initial: (() -> T)?) {

    private val local: java.lang.ThreadLocal<T> =
        if (initial == null) java.lang.ThreadLocal() else java.lang.ThreadLocal.withInitial(initial)

    actual fun get(): T = local.get()

    actual fun set(value: T) = local.set(value)

    actual fun remove() = local.remove()
}

/** `AtomicLong`. */
actual class AtomicCounter actual constructor() {

    private val value = java.util.concurrent.atomic.AtomicLong()

    actual fun get(): Long = value.get()

    actual fun incrementAndGet(): Long = value.incrementAndGet()

    override fun toString(): String = value.toString()
}

/** `AtomicBoolean`. */
actual class AtomicFlag actual constructor() {

    private val value = java.util.concurrent.atomic.AtomicBoolean()

    actual fun get(): Boolean = value.get()

    actual fun set(newValue: Boolean) = value.set(newValue)

    actual fun compareAndSet(expect: Boolean, update: Boolean): Boolean = value.compareAndSet(expect, update)
}
