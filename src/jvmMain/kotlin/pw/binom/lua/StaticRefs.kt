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

    fun removeIfMatches(value: Any?): Boolean {
        synchronized(map) {
            val it = map.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value === value) {
                    it.remove()
                    return true
                }
            }
        }
        return false
    }
}
