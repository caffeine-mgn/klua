package pw.binom.lua

import kotlinx.cinterop.*
import platform.internal_lua.*
import kotlin.native.internal.createCleaner

expect class NativePtr1
expect class CFunction1<T : Function<*>>(rawPtr: NativePtr1) : CPointed1
expect abstract class CPointed1(rawPtr: NativePtr1)
expect class CPointer1<T : CPointed1>

expect fun <T : CPointed1> CPointer1<T>?.toLong1(): Long

expect class lua_State1 : CStructVar1
expect abstract class CVariable1(rawPtr: NativePtr1) : CPointed1
expect abstract class CStructVar1(rawPtr: NativePtr1) : CVariable1
typealias lua_KContext1 = Long
typealias COpaquePointer1 = CPointer1<out CPointed1>
typealias lua_CFunction1 = CPointer1<CFunction1<(CPointer1<lua_State1>?) -> Int>>

expect value class StableRef1<out T : Any> internal constructor(private val stablePtr: COpaquePointer1) {
    companion object {
        fun <T : Any> create(any: T): StableRef1<T>
    }

    fun asCPointer(): COpaquePointer1
    fun dispose()
    fun get(): T
}

expect inline fun <reified T : Any> CPointer1<*>.asStableRef1(): StableRef1<T>

typealias LuaState = CPointer1<lua_State1>

internal expect class Heap {
    companion object {
        val PTR_SIZE: Int
    }

    fun getPtrFromPtr(ptr: COpaquePointer1): COpaquePointer1?
    fun setPtrFromPtr(ptr: COpaquePointer1, value: COpaquePointer1?)
}

expect val LUA_ERRMEM1: Int
expect val LUA_ERRERR1: Int
expect val LUA_ERRRUN1: Int
expect val LUA_OK1: Int
expect val LUA_MULTRET1: Int
expect val LUA_ERRSYNTAX1: Int
expect val LUA_REGISTRYINDEX1: Int
expect val LUA_TNIL1: Int
expect val LUA_TFUNCTION1: Int
expect val LUA_TNONE1: Int
expect val LUA_TNUMBER1: Int
expect val LUA_TBOOLEAN1: Int
expect val LUA_TSTRING1: Int
expect val LUA_TTABLE1: Int
expect val LUA_TUSERDATA1: Int
expect val LUA_TTHREAD1: Int
expect val LUA_TLIGHTUSERDATA1: Int

expect val CLOSURE_FUNCTION: lua_CFunction1
expect val AC_CLOSURE_PTR: CPointer1<CFunction1<() -> Unit>>
expect val closureGc: lua_CFunction1
expect val userdataGc: lua_CFunction1

internal fun createCleaner1(state: LuaStateAndLib, ref: LuaRef): Any = createCleaner1(state to ref) {
    it.first.disposeRef(it.second)
}

expect fun <T> createCleaner1(obj: T, func: (T) -> Unit): Any

//-------------------------------------------//



actual typealias NativePtr1 = kotlin.native.internal.NativePtr
actual typealias CFunction1<T> = CFunction<T>
actual typealias CPointed1 = CPointed
actual typealias CPointer1<T> = CPointer<T>

actual fun <T : CPointed1> CPointer1<T>?.toLong1(): Long = this.toLong()
actual typealias lua_State1 = lua_State

actual typealias CVariable1 = CVariable
actual typealias CStructVar1 = CStructVar
// actual typealias StableRef1<T> = StableRef<T>

actual value class StableRef1<out T : Any> constructor(private val stablePtr: COpaquePointer1) {
    actual companion object {
        actual fun <T : Any> create(any: T): StableRef1<T> = StableRef1(StableRef.create(any).asCPointer())
    }

    actual fun asCPointer(): COpaquePointer1 = stablePtr

    actual fun dispose() {
        stablePtr.asStableRef<Any>().dispose()
    }

    actual fun get(): T = stablePtr.asStableRef<Any>().get() as T
}

actual inline fun <reified T : Any> CPointer1<*>.asStableRef1(): StableRef1<T> =
    StableRef1(this.asStableRef<T>().asCPointer())

internal actual class Heap {
    actual companion object {
        actual val PTR_SIZE: Int
            get() = sizeOf<klua_pointer>().convert()
    }

    actual fun getPtrFromPtr(ptr: COpaquePointer1): COpaquePointer1? =
        ptr.reinterpret<klua_pointer>().pointed.pointer

    actual fun setPtrFromPtr(ptr: COpaquePointer1, value: COpaquePointer1?) {
        val c = ptr.reinterpret<klua_pointer>()
        c.pointed.pointer = value
    }
}

actual val LUA_ERRMEM1
    get() = LUA_ERRMEM
actual val LUA_ERRERR1
    get() = LUA_ERRERR
actual val LUA_ERRRUN1
    get() = LUA_ERRRUN
actual val LUA_OK1
    get() = LUA_OK
actual val LUA_MULTRET1
    get() = LUA_MULTRET
actual val LUA_ERRSYNTAX1
    get() = LUA_ERRSYNTAX
actual val LUA_REGISTRYINDEX1
    get() = LUA_REGISTRYINDEX
actual val LUA_TNIL1
    get() = LUA_TNIL
actual val LUA_TFUNCTION1
    get() = LUA_TFUNCTION
actual val LUA_TNONE1
    get() = LUA_TNONE
actual val LUA_TNUMBER1
    get() = LUA_TNUMBER
actual val LUA_TBOOLEAN1
    get() = LUA_TBOOLEAN
actual val LUA_TSTRING1
    get() = LUA_TSTRING
actual val LUA_TTABLE1
    get() = LUA_TTABLE
actual val LUA_TUSERDATA1
    get() = LUA_TUSERDATA
actual val LUA_TTHREAD1
    get() = LUA_TTHREAD
actual val LUA_TLIGHTUSERDATA1
    get() = LUA_TLIGHTUSERDATA

@OptIn(ExperimentalStdlibApi::class)
actual fun <T> createCleaner1(obj: T, func: (T) -> Unit): Any = createCleaner(obj to func) {
    it.second(it.first)
}
