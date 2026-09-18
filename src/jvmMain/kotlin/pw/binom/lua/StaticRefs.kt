package pw.binom.lua

import java.util.concurrent.atomic.AtomicInteger

internal object StaticRefs {
    private val counter = AtomicInteger(1)
    private val map = HashMap<Long, Any?>()

    fun intern(value: Any?): Long {
        val key = counter.getAndIncrement().toLong()
        synchronized(map) { map[key] = value }
        return key
    }

    fun store(key: Long, value: Any?) {
        synchronized(map) { map[key] = value }
    }

    fun get(key: Long?): Any? {
        if (key == null) return null
        synchronized(map) { return map[key] }
    }

    fun dispose(key: Long?) {
        if (key == null) return
        synchronized(map) { map.remove(key) }
    }

    /**
     * Removes every entry whose stored value is identity-equal to [value].
     * Returns the number of entries removed. The single-thread implementation
     * short-circuited after the first match, leaving N-1 entries stranded
     * after `add(obj)` x N; with the concurrent-engine case this becomes
     * an unbounded leak because each entry is only freed when its
     * LightUserData wrapper is itself GC'd.
     */
    fun removeIfMatches(value: Any?): Int {
        synchronized(map) {
            var count = 0
            val it = map.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value === value) {
                    it.remove()
                    count++
                }
            }
            return count
        }
    }

    /** JVM-test hook: number of live AC/static-ref entries. */
    internal val size: Int
        get() = synchronized(map) { map.size }
}
