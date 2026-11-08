package com.drive.drive_manager.simulator.platform

/**
 * What the game's core needs from the platform it runs on, in one place: the JVM's implementation today. When the
 * core moves to eras-engine (commonMain), this object and the small types below become `expect` declarations with one
 * `actual` per target; the rest of the core stays as it is. Nothing else in the core may use a JVM-only API.
 */
object Platform {

    /** A seed for a chance nobody seeded (`new java.util.Random()`): different each time. */
    @JvmStatic
    fun randomSeed(): Long = java.util.Random().nextLong()

    /**
     * The implementations of `type` the program offers as services (`java.util.ServiceLoader`, from the
     * META-INF/services files of `type`'s class loader), in the order found: how the core finds what the server fills in
     * for it (the cards, the bot's resources) without depending on the server.
     */
    @JvmStatic
    fun <T : Any> discover(type: kotlin.reflect.KClass<T>): List<T> =
        java.util.ServiceLoader.load(type.java, type.java.classLoader).toList()

    /** The wall clock in milliseconds since the epoch (`System.currentTimeMillis()`): the log's and events' times. */
    @JvmStatic
    fun currentTimeMillis(): Long = System.currentTimeMillis()

    /** A monotonic clock in nanoseconds (`System.nanoTime()`): the bot's time budgets. */
    @JvmStatic
    fun nanoTime(): Long = System.nanoTime()

    /** Now, as an [Instant] (`Instant.now()`). */
    @JvmStatic
    fun now(): Instant = Instant.now()

    /** A span of `seconds` seconds (`Duration.ofSeconds`). */
    @JvmStatic
    fun seconds(seconds: Long): Duration = Duration.ofSeconds(seconds)

    /** A random UUID's 32 hex digits, without the dashes (`UUID.randomUUID().toString().replace("-", "")`). */
    @JvmStatic
    fun randomHexId(): String = java.util.UUID.randomUUID().toString().replace("-", "")

    /** A map safe to share between threads (`ConcurrentHashMap`: no null keys or values). */
    @JvmStatic
    fun <K : Any, V : Any> concurrentMap(): MutableMap<K, V> = java.util.concurrent.ConcurrentHashMap()

    /** A map that tells its keys apart by identity, not by `equals` (`IdentityHashMap`). */
    @JvmStatic
    fun <K, V> identityMap(): MutableMap<K, V> = java.util.IdentityHashMap()

    /**
     * An empty set sorted as `o` is, when `o` is a sorted set (`new TreeSet(o.comparator())`), else null: so a copy keeps
     * the original's kind.
     */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun <T> emptySortedSetLike(o: Collection<T>): MutableSet<T>? =
        if (o is java.util.SortedSet<*>) java.util.TreeSet(o.comparator() as Comparator<in T>?) else null

    /** As [emptySortedSetLike], for maps (`new TreeMap(o.comparator())` for a sorted map, else null). */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun <K, V> emptySortedMapLike(o: Map<*, *>): MutableMap<K, V>? =
        if (o is java.util.SortedMap<*, *>) java.util.TreeMap(o.comparator() as Comparator<in K>?) else null
}

/**
 * As `map.computeIfAbsent(key, compute)` of Java's maps: atomic on a [Platform.concurrentMap] (the value is computed
 * once per key even with threads racing), unlike Kotlin's `getOrPut`.
 */
fun <K, V : Any> MutableMap<K, V>.computeIfAbsentAtomic(key: K, compute: (K) -> V): V = computeIfAbsent(key, compute)

/** Runs `block` holding `lock`'s monitor (`synchronized (lock) { … }`). */
inline fun <R> withLock(lock: Any, block: () -> R): R = synchronized(lock, block)

/** A point in time (`java.time.Instant` on the JVM): the rooms' creation and emptiness times. */
typealias Instant = java.time.Instant

/** A span of time (`java.time.Duration` on the JVM): the rooms' limits. */
typealias Duration = java.time.Duration

/** Marks a field the JVM's serialization and Jackson skip (`@kotlin.jvm.Transient`). */
typealias Transient = kotlin.jvm.Transient

/** A value of each thread its own (`java.lang.ThreadLocal`), starting as `initial()` (or null without one). */
class LocalValue<T>(initial: (() -> T)? = null) {

    private val local: java.lang.ThreadLocal<T> =
        if (initial == null) java.lang.ThreadLocal() else java.lang.ThreadLocal.withInitial(initial)

    fun get(): T = local.get()

    fun set(value: T) = local.set(value)

    fun remove() = local.remove()
}

/** A counter safe to share between threads (`AtomicLong`); reads as its value. */
class AtomicCounter {

    private val value = java.util.concurrent.atomic.AtomicLong()

    fun get(): Long = value.get()

    fun incrementAndGet(): Long = value.incrementAndGet()

    override fun toString(): String = value.toString()
}

/** A flag safe to share between threads (`AtomicBoolean`). */
class AtomicFlag {

    private val value = java.util.concurrent.atomic.AtomicBoolean()

    fun get(): Boolean = value.get()

    fun set(newValue: Boolean) = value.set(newValue)

    fun compareAndSet(expect: Boolean, update: Boolean): Boolean = value.compareAndSet(expect, update)
}
