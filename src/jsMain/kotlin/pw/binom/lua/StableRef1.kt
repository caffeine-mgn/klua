package pw.binom.lua

private var internalPtrCounter = 0
private val stablePtrTable = HashMap<NativePtr1, Any>()

private fun getFreePtr(): Int {
    do {
        val ptr = internalPtrCounter++
        if (ptr !in stablePtrTable) {
            return ptr
        }
    } while (false)
    throw IllegalStateException("No free pointers")
}

actual value class StableRef1<out T : Any> internal constructor(private val stablePtr: COpaquePointer1) {
    actual companion object {
        actual fun <T : Any> create(any: T): StableRef1<T> = StableRef1(CPointer1(getFreePtr()))
    }

    actual fun asCPointer(): COpaquePointer1 = stablePtr

    actual fun dispose() {
        stablePtrTable.remove(stablePtr.value)
    }

    actual fun get(): T = stablePtrTable[stablePtr.value] as T
}