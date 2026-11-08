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
}
