package pw.binom.lua

import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.khronos.webgl.set

actual typealias NativePtr1 = Int

actual abstract class CPointed1 actual constructor(var rawPtr: NativePtr1)
actual class CFunction1<T : Function<*>> actual constructor(rawPtr: NativePtr1) : CPointed1(rawPtr)
actual class CPointer1<T : CPointed1> constructor(val value: NativePtr1)

actual class lua_State1 constructor(rawPtr: NativePtr1) : CStructVar1(rawPtr)
actual abstract class CStructVar1 actual constructor(rawPtr: NativePtr1) : CVariable1(rawPtr)


@OptIn(ExperimentalStdlibApi::class)
actual fun <T> createCleaner1(obj: T, func: (T) -> Unit): Any {
    val cleaner = Any()
    RefContainer.push(cleaner) {
        func(obj)
    }
    return cleaner
}

internal actual class Heap(val buf: ArrayBuffer) {
    val HEAP8 = Int8Array(buf)
    fun getInt(ptr: Int): Int {
        val byte0 = HEAP8[ptr + 0]
        val byte1 = HEAP8[ptr + 1]
        val byte2 = HEAP8[ptr + 2]
        val byte3 = HEAP8[ptr + 3]

        return ((byte0.toInt() and 0xFF) shl 24) +
                ((byte1.toInt() and 0xFF) shl 16) +
                ((byte2.toInt() and 0xFF) shl 8) +
                ((byte3.toInt() and 0xFF) shl 0)
    }

    fun setInt(ptr: Int, value: Int) {
        HEAP8[ptr + 0] = (value ushr (8 * (3 - 0))).toByte()
        HEAP8[ptr + 1] = (value ushr (8 * (3 - 1))).toByte()
        HEAP8[ptr + 2] = (value ushr (8 * (3 - 2))).toByte()
        HEAP8[ptr + 3] = (value ushr (8 * (3 - 3))).toByte()
    }
}

internal actual fun getPtrFromPtr(heap: Heap, ptr: COpaquePointer1): COpaquePointer1? {
    val address = heap.getInt(ptr.value)
    if (address == 0) {
        return null
    }
    return COpaquePointer1(address)
}

internal actual fun setPtrFromPtr(heap: Heap, ptr: COpaquePointer1, value: COpaquePointer1?) {
    heap.setInt(ptr = ptr.value, value = value?.value ?: 0)
}

internal actual val PTR_SIZE: Int
    get() = Int.SIZE_BYTES