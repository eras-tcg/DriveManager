package com.drive.drive_manager.simulator.platform

/**
 * A map that iterates as `java.util.HashMap` does, for JavaScript, whose `HashMap` iterates in insertion order: what the
 * server sends from one (a timeline event's data) is written in the same order here. The same buckets as Java's: the key's
 * `hashCode` spread (`h ^ (h >>> 16)`), a table of a power of two that doubles past three quarters full (or when one
 * bucket holds 8 keys and the table is under 64), each bucket in the order its keys came, a copy sized as Java sizes it.
 * (Java turns a crowded bucket of a large table into a tree, which orders its keys otherwise: never with the engine's
 * small maps.)
 */
internal class JavaHashMap<K, V>() : AbstractMutableMap<K, V>() {

    private class Node<K, V>(val hash: Int, override val key: K, override var value: V, var next: Node<K, V>?) : MutableMap.MutableEntry<K, V> {
        override fun setValue(newValue: V): V {
            val old = value
            value = newValue
            return old
        }

        override fun equals(other: Any?): Boolean = other is Map.Entry<*, *> && other.key == key && other.value == value
        override fun hashCode(): Int = (key?.hashCode() ?: 0) xor (value?.hashCode() ?: 0)
        override fun toString(): String = "$key=$value"
    }

    private var table: Array<Node<K, V>?>? = null
    private var count = 0
    /** The size past which the table doubles (before the table exists: the capacity it will have, 0 for the default). */
    private var threshold = 0

    /** `new HashMap<>(m)`: a table sized for the map (`tableSizeFor(size / 0.75 + 1)`), then its entries in its order. */
    constructor(from: Map<out K, V>) : this() {
        val s = from.size
        if (s > 0) {
            val t = (s.toFloat() / LOAD_FACTOR + 1.0f).toInt()
            threshold = tableSizeFor(t)
            for ((k, v) in from) put(k, v)
        }
    }

    override val size: Int get() = count

    private fun hash(key: Any?): Int {
        val h = key?.hashCode() ?: 0
        return h xor (h ushr 16)
    }

    private fun find(key: Any?): Node<K, V>? {
        val tab = table ?: return null
        var e = tab[(tab.size - 1) and hash(key)]
        while (e != null) {
            if (e.key == key) return e
            e = e.next
        }
        return null
    }

    override fun containsKey(key: K): Boolean = find(key) != null

    override fun get(key: K): V? = find(key)?.value

    override fun put(key: K, value: V): V? {
        val h = hash(key)
        val tab = table ?: resize()
        val i = (tab.size - 1) and h
        var e = tab[i]
        if (e == null) {
            tab[i] = Node(h, key, value, null)
        } else {
            var bin = 0
            while (true) {
                if (e!!.key == key) return e.setValue(value)
                bin++
                if (e.next == null) break
                e = e.next
            }
            e.next = Node(h, key, value, null)
            // Java's treeifyBin on a small table: it doubles instead
            if (bin >= TREEIFY_THRESHOLD - 1 && tab.size < MIN_TREEIFY_CAPACITY) resize()
        }
        if (++count > threshold) resize()
        return null
    }

    override fun remove(key: K): V? {
        val tab = table ?: return null
        val i = (tab.size - 1) and hash(key)
        var prev: Node<K, V>? = null
        var e = tab[i]
        while (e != null) {
            if (e.key == key) {
                if (prev == null) tab[i] = e.next else prev.next = e.next
                count--
                return e.value
            }
            prev = e
            e = e.next
        }
        return null
    }

    override fun clear() {
        val tab = table ?: return
        tab.fill(null)
        count = 0
    }

    /** Java's resize: the first table (16, or the size asked for), else twice as large, each bucket split in its order. */
    private fun resize(): Array<Node<K, V>?> {
        val old = table
        val oldCap = old?.size ?: 0
        val newCap = when {
            oldCap > 0 -> oldCap shl 1
            threshold > 0 -> threshold
            else -> DEFAULT_CAPACITY
        }
        threshold = (newCap * LOAD_FACTOR).toInt()
        val tab = arrayOfNulls<Node<K, V>>(newCap)
        table = tab
        if (old != null) {
            for (j in 0 until oldCap) {
                var e = old[j]
                var loHead: Node<K, V>? = null
                var loTail: Node<K, V>? = null
                var hiHead: Node<K, V>? = null
                var hiTail: Node<K, V>? = null
                while (e != null) {
                    val next = e.next
                    e.next = null
                    if ((e.hash and oldCap) == 0) {
                        if (loTail == null) loHead = e else loTail.next = e
                        loTail = e
                    } else {
                        if (hiTail == null) hiHead = e else hiTail.next = e
                        hiTail = e
                    }
                    e = next
                }
                tab[j] = loHead
                tab[j + oldCap] = hiHead
            }
        }
        return tab
    }

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> = object : AbstractMutableSet<MutableMap.MutableEntry<K, V>>() {
        override val size: Int get() = count

        override fun add(element: MutableMap.MutableEntry<K, V>): Boolean = throw UnsupportedOperationException()

        override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> = object : MutableIterator<MutableMap.MutableEntry<K, V>> {
            private var index = 0
            private var next: Node<K, V>? = advance(null)
            private var last: Node<K, V>? = null

            private fun advance(from: Node<K, V>?): Node<K, V>? {
                if (from?.next != null) return from.next
                val tab = table ?: return null
                while (index < tab.size) {
                    val e = tab[index++]
                    if (e != null) return e
                }
                return null
            }

            override fun hasNext(): Boolean = next != null

            override fun next(): MutableMap.MutableEntry<K, V> {
                val e = next ?: throw NoSuchElementException()
                last = e
                next = advance(e)
                return e
            }

            override fun remove() {
                val e = last ?: throw IllegalStateException()
                last = null
                this@JavaHashMap.remove(e.key)
            }
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
        const val TREEIFY_THRESHOLD = 8
        const val MIN_TREEIFY_CAPACITY = 64
        const val MAX_CAPACITY = 1 shl 30

        /** Java's `tableSizeFor`: the power of two at or above `c`. */
        fun tableSizeFor(c: Int): Int {
            val n = if (c - 1 <= 0) 0 else -1 ushr (c - 1).countLeadingZeroBits()
            return if (n < 0) 1 else if (n >= MAX_CAPACITY) MAX_CAPACITY else n + 1
        }
    }
}
