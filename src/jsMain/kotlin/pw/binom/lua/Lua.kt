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

actual abstract class CVariable1 actual constructor(rawPtr: NativePtr1) : CPointed1(rawPtr)

actual fun <T : CPointed1> CPointer1<T>?.toLong1(): Long = this?.value?.toLong() ?: 0


@OptIn(ExperimentalStdlibApi::class)
actual fun <T> createCleaner1(obj: T, func: (T) -> Unit): Any {
    val cleaner = Any()
    RefContainer.push(cleaner) {
        func(obj)
    }
    return cleaner
}

internal actual class Heap(buf: ArrayBuffer) {
    actual companion object {
        actual val PTR_SIZE: Int
            get() = 4
    }

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

    actual fun getPtrFromPtr(ptr: COpaquePointer1): COpaquePointer1? {
        val p = getInt(ptr.value).takeIf { it != 0 } ?: return null
        return COpaquePointer1(p)
    }

    actual fun setPtrFromPtr(ptr: COpaquePointer1, value: COpaquePointer1?) {
        setInt(ptr.value, value?.value ?: 0)
    }
}

actual val LUA_ERRMEM1: Int
    get() = 4
actual val LUA_ERRERR1: Int
    get() = 5
actual val LUA_ERRRUN1: Int
    get() = 2
actual val LUA_OK1: Int
    get() = 0
actual val LUA_MULTRET1: Int
    get() = -1
actual val LUA_ERRSYNTAX1: Int
    get() = 3
actual val LUA_REGISTRYINDEX1: Int
    get() = -LUAI_MAXSTACK - 1000
val LUAI_MAXSTACK: Int
    get() = LUALIB_INSTANCE.maxstack
actual val LUA_TNIL1: Int
    get() = 0
actual val LUA_TFUNCTION1: Int
    get() = 6
actual val LUA_TNONE1: Int
    get() = -1
actual val LUA_TNUMBER1: Int
    get() = 3
actual val LUA_TBOOLEAN1: Int
    get() = 1
actual val LUA_TSTRING1: Int
    get() = 4
actual val LUA_TTABLE1: Int
    get() = 5
actual val LUA_TUSERDATA1: Int
    get() = 7
actual val LUA_TTHREAD1: Int
    get() = 8
actual val LUA_TLIGHTUSERDATA1: Int
    get() = 2

private val closureCall: (LuaState) -> Int = { state ->
    StdOut.info("invoked some closure")
    callClosure(state)
    try {
        callClosure(state!!)
    } catch (e: Throwable) {
        e.printStackTrace()
        LUALIB_INSTANCE.luaL_error1(state, e.toString())
        0
    }
}
private val closureCallPtr by lazy {
    lua_CFunction1(WASM_INSTANCE.addFunction(closureCall, "ii"))
}

actual val CLOSURE_FUNCTION: lua_CFunction1
    get() = closureCallPtr

private val closureStabCall: () -> Unit = {

}

internal val closureCall1 by lazy {
    val p = WASM_INSTANCE.addFunction({}, "v")

    CPointer1<CFunction1<() -> Unit>>(p)
}

actual val AC_CLOSURE_PTR: CPointer1<CFunction1<() -> Unit>>
    get() = closureCall1
actual val closureGc: lua_CFunction1
    get() = lua_CFunction1(LUALIB_INSTANCE.getClosureGcFuncAddress())
actual val userdataGc: lua_CFunction1
    get() = lua_CFunction1(LUALIB_INSTANCE.getUserdataGcFuncAddress())