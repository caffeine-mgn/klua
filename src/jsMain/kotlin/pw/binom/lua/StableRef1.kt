package pw.binom.lua

import kotlin.reflect.KClass

private var internalPtrCounter = 0
private val stablePtrTable = HashMap<NativePtr1, Any>()

private fun getFreePtr(): Int {
    do {
        val ptr = internalPtrCounter++
        if (ptr == 0) {
            continue
        }
        if (ptr !in stablePtrTable) {
            return ptr
        }
    } while (true)
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

fun <T : Any> CPointer1<*>.internalAsStableRef2(clazz: KClass<T>): StableRef1<T> {
    return StableRef1(this)
//    val o = stablePtrTable[this.value]
//        ?: throw IllegalArgumentException("Can't find any object on stable pointer 0x${this.toLong1().toString(16)}")
//    return
}

actual inline fun <reified T : Any> CPointer1<*>.asStableRef1(): StableRef1<T> =
    internalAsStableRef2(T::class)