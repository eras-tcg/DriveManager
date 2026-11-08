package com.drive.drive_manager.simulator.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The platform seam behaves the same on every target (the JVM's and JavaScript's actuals). */
class PlatformTest {

    private data class Key(val name: String)

    @Test
    fun anIdentityMapTellsEqualKeysApart() {
        val a = Key("a")
        val alsoA = Key("a")
        val map = Platform.identityMap<Key, Int>()
        map[a] = 1
        map[alsoA] = 2
        assertEquals(2, map.size)
        assertEquals(1, map[a])
        assertEquals(2, map[alsoA])
        assertNull(map[Key("a")])
        assertEquals(1, map.put(a, 3))
        val it = map.entries.iterator()
        while (it.hasNext()) if (it.next().key === alsoA) it.remove()
        assertEquals(mapOf(a to 3), map.toMap())
        assertEquals(3, map.remove(a))
        assertTrue(map.isEmpty())
    }

    @Test
    fun localValuesStartAsTheirInitialValue() {
        val counter = LocalValue { 7 }
        assertEquals(7, counter.get())
        counter.set(8)
        assertEquals(8, counter.get())
        counter.remove()
        assertEquals(7, counter.get())
        assertNull(LocalValue<String?>().get())
    }

    @Test
    fun countersFlagsAndLocks() {
        val counter = AtomicCounter()
        assertEquals(1, counter.incrementAndGet())
        assertEquals("1", counter.toString())
        val flag = AtomicFlag()
        assertTrue(flag.compareAndSet(false, true))
        assertFalse(flag.compareAndSet(false, true))
        assertTrue(flag.get())
        assertEquals(5, withLock(this) { 5 })
        val cache = Platform.concurrentMap<String, Int>()
        assertEquals(3, cache.computeIfAbsentAtomic("abc") { it.length })
        assertEquals(3, cache.computeIfAbsentAtomic("abc") { 99 })
    }

    @Test
    fun idsAndClocks() {
        val id = Platform.randomHexId()
        assertTrue(Regex("[0-9a-f]{32}").matches(id), id)
        assertNotEquals(id, Platform.randomHexId())
        assertTrue(Platform.currentTimeMillis() > 1_700_000_000_000L)
        val start = Platform.nanoTime()
        assertTrue(Platform.nanoTime() >= start)
        assertNull(Platform.emptySortedSetLike(listOf(1)))
        assertEquals(Platform.seconds(30), Platform.seconds(30))
    }

    /** Platform.hashMap iterates as java.util.HashMap (the orders below are Java's; on JavaScript, JavaHashMap's). */
    @Test
    fun aHashMapIteratesInJavasOrder() {
        val m = Platform.hashMap<String, Any?>()
        m["byEffect"] = true
        m["exhausted"] = false
        m["targetId"] = "c1"
        assertEquals(listOf("targetId", "byEffect", "exhausted"), m.keys.toList())
        // Copies are sized as Java sizes them (a table of 2, then 4, for one entry): the order follows
        val c = Platform.hashMap(linkedMapOf<String, Any?>("cause" to "x"))
        c["byCard"] = 1
        assertEquals(listOf("byCard", "cause"), c.keys.toList())
        val c3 = Platform.hashMap(linkedMapOf<String, Any?>("ownerId" to 1, "frontLine" to 1, "to" to 1))
        c3["byCard"] = 1
        assertEquals(listOf("to", "ownerId", "frontLine", "byCard"), c3.keys.toList())
        val e = Platform.hashMap(emptyMap<String, Any?>())
        e["byCard"] = 1
        e["damage"] = 1
        assertEquals(listOf("damage", "byCard"), e.keys.toList())
        // Growing past several tables, with keys removed and one put again
        val big = Platform.hashMap<String, Any?>()
        for (i in 0 until 40) big["k$i"] = i
        for (i in 0 until 40 step 3) big.remove("k$i")
        big["k3"] = 0
        assertEquals(("k31, k11, k10, k32, k13, k35, k34, k37, k14, k17, k16, k38, k19, k1, k2, k20, k3, k4, k5, k22, k7, k8, k23, k26, " +
            "k25, k28, k29").split(", "), big.keys.toList())
        assertEquals(27, big.size)
        assertEquals(0, big["k3"])
        assertNull(big["k0"])
        val it = big.entries.iterator()
        while (it.hasNext()) if (it.next().key.length == 3) it.remove()
        assertEquals(listOf("k1", "k2", "k3", "k4", "k5", "k7", "k8"), big.keys.toList())
    }
}
