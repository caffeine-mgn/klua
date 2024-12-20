@file:OptIn(ExperimentalForeignApi::class)

package pw.binom.lua

import kotlinx.cinterop.*
import platform.internal_lua.*
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner

typealias lua_KContext1 = Long
@OptIn(ExperimentalForeignApi::class)
typealias lua_CFunction1 = CPointer<CFunction<(CPointer<lua_State>?) -> Int>>

typealias LuaState = CPointer<lua_State>

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
internal fun createCleaner1(state: LuaStateAndLib, ref: LuaRef): Any = createCleaner(state to ref) {
    it.first.state.disposeRef(it.second)
}

@OptIn(ExperimentalForeignApi::class)
internal object Heap {
    val PTR_SIZE: Int
        get() = sizeOf<klua_pointer>().convert()

    fun getPtrFromPtr(ptr: COpaquePointer): COpaquePointer? =
        ptr.reinterpret<klua_pointer>().pointed.pointer

    fun setPtrFromPtr(ptr: COpaquePointer, value: COpaquePointer?) {
        val c = ptr.reinterpret<klua_pointer>()
        c.pointed.pointer = value
    }
}

@OptIn(ExperimentalStdlibApi::class, ExperimentalNativeApi::class)
fun <T> createCleaner1(obj: T, func: (T) -> Unit): Any = createCleaner(obj to func) {
    it.second(it.first)
}
