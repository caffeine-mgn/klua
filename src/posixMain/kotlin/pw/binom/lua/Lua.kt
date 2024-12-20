@file:OptIn(ExperimentalForeignApi::class)

package pw.binom.lua

import kotlinx.cinterop.*
import platform.internal_lua.*
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner

typealias lua_KContext1 = Long
@OptIn(ExperimentalForeignApi::class)
typealias lua_CFunction1 = CPointer<CFunction<(CPointer<lua_State>?) -> Int>>

//expect value class StableRef1<out T : Any> internal constructor(private val stablePtr: COpaquePointer1) {
//    companion object {
//        fun <T : Any> create(any: T): StableRef1<T>
//    }
//
//    fun asCPointer(): COpaquePointer
//    fun dispose()
//    fun get(): T
//}

typealias LuaState = CPointer<lua_State>

//internal expect class Heap {
//    companion object {
//        val PTR_SIZE: Int
//    }
//
//    fun getPtrFromPtr(ptr: COpaquePointer1): COpaquePointer1?
//    fun setPtrFromPtr(ptr: COpaquePointer1, value: COpaquePointer1?)
//}

//expect val LUA_ERRMEM1: Int
//expect val LUA_ERRERR1: Int
//expect val LUA_ERRRUN1: Int
//expect val LUA_OK1: Int
//expect val LUA_MULTRET1: Int
//expect val LUA_ERRSYNTAX1: Int
//expect val LUA_REGISTRYINDEX1: Int
//expect val LUA_TNIL1: Int
//expect val LUA_TFUNCTION1: Int
//expect val LUA_TNONE1: Int
//expect val LUA_TNUMBER1: Int
//expect val LUA_TBOOLEAN1: Int
//expect val LUA_TSTRING1: Int
//expect val LUA_TTABLE1: Int
//expect val LUA_TUSERDATA1: Int
//expect val LUA_TTHREAD1: Int
//expect val LUA_TLIGHTUSERDATA1: Int


@OptIn(ExperimentalNativeApi::class)
internal fun createCleaner1(state: LuaStateAndLib, ref: LuaRef): Any = createCleaner(state to ref) {
    it.first.state.disposeRef(it.second)
}


// -------------------------------------------//

// actual typealias StableRef1<T> = StableRef<T>

//actual value class StableRef1<out T : Any> constructor(private val stablePtr: COpaquePointer1) {
//    actual companion object {
//        actual fun <T : Any> create(any: T): StableRef1<T> = StableRef1(StableRef.create(any).asCPointer())
//    }
//
//    actual fun asCPointer(): COpaquePointer1 = stablePtr
//
//    actual fun dispose() {
//        stablePtr.asStableRef<Any>().dispose()
//    }
//
//    actual fun get(): T = stablePtr.asStableRef<Any>().get() as T
//}

@OptIn(ExperimentalForeignApi::class)
internal class Heap {
    companion object {
        val PTR_SIZE: Int
            get() = sizeOf<klua_pointer>().convert()
    }

    fun getPtrFromPtr(ptr: COpaquePointer): COpaquePointer? =
        ptr.reinterpret<klua_pointer>().pointed.pointer

    fun setPtrFromPtr(ptr: COpaquePointer, value: COpaquePointer?) {
        val c = ptr.reinterpret<klua_pointer>()
        c.pointed.pointer = value
    }
}

//actual val LUA_ERRMEM1
//    get() = LUA_ERRMEM
//actual val LUA_ERRERR1
//    get() = LUA_ERRERR
//actual val LUA_ERRRUN1
//    get() = LUA_ERRRUN
//actual val LUA_OK1
//    get() = LUA_OK
//actual val LUA_MULTRET1
//    get() = LUA_MULTRET
//actual val LUA_ERRSYNTAX1
//    get() = LUA_ERRSYNTAX
//actual val LUA_REGISTRYINDEX1
//    get() = LUA_REGISTRYINDEX
//actual val LUA_TNIL1
//    get() = LUA_TNIL
//actual val LUA_TFUNCTION1
//    get() = LUA_TFUNCTION
//actual val LUA_TNONE1
//    get() = LUA_TNONE
//actual val LUA_TNUMBER1
//    get() = LUA_TNUMBER
//actual val LUA_TBOOLEAN1
//    get() = LUA_TBOOLEAN
//actual val LUA_TSTRING1
//    get() = LUA_TSTRING
//actual val LUA_TTABLE1
//    get() = LUA_TTABLE
//actual val LUA_TUSERDATA1
//    get() = LUA_TUSERDATA
//actual val LUA_TTHREAD1
//    get() = LUA_TTHREAD
//actual val LUA_TLIGHTUSERDATA1
//    get() = LUA_TLIGHTUSERDATA

@OptIn(ExperimentalStdlibApi::class, ExperimentalNativeApi::class)
fun <T> createCleaner1(obj: T, func: (T) -> Unit): Any = createCleaner(obj to func) {
    it.second(it.first)
}
