package pw.binom.lua

import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set

internal actual class LuaLib(val module: LuaWasm) {
    //    private val asmd = module.asm.asDynamic()
    private val md = module.asDynamic()
    private val decoder = TextDecoder()
    private val encoder = TextEncoder()

    actual val heap = Heap(module.HEAP8.buffer)

    private inline fun malloc(size: Int): NativePtr1 = md._klua_malloc(size)
    private inline fun free(ptr: NativePtr1): Unit = md._klua_free(ptr)
    private fun strlen(ptr: NativePtr1): Int {
        var ptr = ptr
        var c = 0
        while (true) {
            val v = heap.HEAP8[ptr]
            if (v == 0.toByte()) {
                break
            }
            c++
            ptr++
        }
        return c
    }

    private fun readString(ptr: NativePtr1): String {
        val size = strlen(ptr)
        val view = Int8Array(heap.HEAP8.buffer, ptr, size)
        return decoder.decode(view)
    }

    private fun <T> writeString(string: String, f: (NativePtr1) -> T): T {
        val buf = encoder.encode(string)

        val ptr = malloc(string.length + 1)
        heap.HEAP8.set(Int8Array(encoder.encode(string).buffer), ptr)
        heap.HEAP8[ptr + buf.length] = 0.toByte()
        return try {
            f(ptr)
        } finally {
            free(ptr)
        }
    }

    actual fun luaL_newstate1(): LuaState? = LuaState(md._luaL_newstate().unsafeCast<Int>())
    actual fun luaL_openlibs1(state: LuaState): Unit {
        md._luaL_openlibs(state.value)
    }

    actual fun lua_getglobal1(state: LuaState, name: String): Int =
        writeString(name) { ptr ->
            md._lua_getglobal(state?.value ?: 0, ptr)
        }

    actual fun lua_setglobal1(state: LuaState, name: String): Unit {
        writeString(name) { ptr ->
            md._lua_setglobal(state?.value ?: 0, ptr)
        }
    }

    actual fun lua_close1(state: LuaState): Unit {
        md._lua_close(state.value)
    }

    actual fun lua_gettop1(state: LuaState): Int = md._lua_gettop(state.value).unsafeCast<Int>()
    actual fun lua_tostring1(state: LuaState, i: Int): String? {
        val ptr = md._lua_tolstring(state?.value ?: 0, i, 0).unsafeCast<NativePtr1>()
//        val ptr = asmd.lua_tostring(state?.value ?: 0, i).unsafeCast<NativePtr1>()
        if (ptr == 0) {
            return null
        }
        return readString(ptr)
    }

    actual fun luaL_tolstring1(state: LuaState, i: Int): String? {
        val ptr = md._luaL_tolstring1(state?.value ?: 0, i).unsafeCast<NativePtr1>()
        if (ptr == 0) {
            return null
        }
        return readString(ptr)
    }

    actual fun luaL_ref1(state: LuaState, t: Int): Int = md._luaL_ref(state.value, t)
    actual fun luaL_unref1(state: LuaState, t: Int, ref: Int): Unit = md._luaL_unref(state?.value ?: 0, t, ref)
    actual fun lua_isstring1(state: LuaState, idx: Int): Int = md._lua_isstring(state?.value ?: 0, idx)
    actual fun lua_pushvalue1(state: LuaState, idx: Int): Unit = md._lua_pushvalue(state?.value ?: 0, idx)
    actual fun lua_type1(state: LuaState, idx: Int): Int = md._lua_type(state?.value ?: 0, idx)
    actual fun lua_rawgeti1(state: LuaState, t: Int, ref: Int): Int =
        md._lua_rawgeti(state?.value ?: 0, t, ref).unsafeCast<Int>()

    actual fun lua_rotate1(state: LuaState, idx: Int, n: Int): Unit = md._lua_rotate(state?.value ?: 0, idx, n)
    actual fun lua_newuserdatauv1(state: LuaState, sz: Int, nuvalue: Int): COpaquePointer1? =
        md._lua_settop(state?.value ?: 0, sz, nuvalue).unsafeCast<Int>().ptr

    actual fun lua_settop1(state: LuaState, idx: Int): Unit = md._lua_settop(state?.value ?: 0, idx)
    actual fun lua_pcallk1(state: LuaState, nargs: Int, nresults: Int, errfunc: Int, ctx: lua_KContext1, k: lua_CFunction1?): Int =
        md._lua_pcallk(
            state?.value ?: 0,
            nargs,
            nresults,
            errfunc,
            ctx,
            k?.value ?: 0
        ).unsafeCast<Int>()

    actual fun lua_topointer1(L: LuaState?, idx: Int): COpaquePointer1? =
        md._lua_topointer(
            L?.value ?: 0,
            idx
        ).unsafeCast<Int>().ptr

    actual fun luaL_traceback1(L: LuaState?, L1: LuaState?, msg: String?, level: Int): Unit {
        if (msg == null) {
            md._luaL_traceback1(
                L?.value ?: 0,
                L1?.value ?: 0,
                0,
                level
            )
        } else {
            writeString(msg) { ptr ->
                md._luaL_traceback1(
                    L?.value ?: 0,
                    L1?.value ?: 0,
                    ptr,
                    level
                )
            }
        }
    }

    actual fun luaL_loadstring1(L: LuaState?, s: String): Int =
        writeString(s) { ptr ->
            md._luaL_loadstring(
                L?.value ?: 0,
                ptr
            )
        }

    actual fun lua_pushnil1(L: LuaState?): Unit {
        md._lua_pushnil(
            L?.value ?: 0
        )
    }

    actual fun luaL_error1(L: LuaState?, message: String?): Int =
        if (message != null) {
            writeString(message) { messagePtr ->
                md.luaL_error(
                    L?.value ?: 0,
                    messagePtr)
            }
        } else {
            md.luaL_error(
                L?.value ?: 0,
                null
            )
        }

    actual fun lua_pushcclosure1(L: LuaState?, fn: lua_CFunction1?, n: Int): Unit {
        md._lua_pushcclosure(
            L?.value ?: 0,
            fn?.value ?: 0,
            n,
        )
    }

    actual fun lua_createtable1(L: LuaState?, narr: Int, nrec: Int): Unit =
        md._lua_createtable(
            L?.value ?: 0,
            narr,
            nrec,
        )

    actual fun lua_pushnumber1(L: LuaState?, n: Double): Unit = md._lua_pushnumber(L?.value ?: 0, n)
    actual fun lua_pushinteger1(L: LuaState?, n: Long): Unit = md._lua_pushinteger(L?.value ?: 0, n)
    actual fun lua_pushboolean1(L: LuaState?, n: Int): Unit = md._lua_pushboolean(L?.value ?: 0, n)
    actual fun lua_pushstring1(L: LuaState?, n: String): Unit {
        writeString(n) { ptr ->
            md._lua_pushstring(
                L?.value ?: 0,
                ptr
            )
        }
    }

    actual fun lua_settable1(L: LuaState?, idx: Int): Unit = md._lua_settable(
        L?.value ?: 0,
        idx)
    actual fun lua_setmetatable1(L: LuaState?, idx: Int): Int = md._lua_setmetatable(
        L?.value ?: 0,
        idx)
    actual fun lua_pushlightuserdata1(L: LuaState?, idx: COpaquePointer1?): Unit =
        md._lua_pushlightuserdata(
            L?.value ?: 0,
            idx?.value ?: 0,
        )

    actual fun lua_tonumberx1(L: LuaState?, idx: Int): Double = md._lua_tonumberx(L?.value ?: 0, idx)
    actual fun lua_toboolean1(L: LuaState?, idx: Int): Int = md._lua_toboolean(L?.value ?: 0, idx)
    actual fun lua_getupvalue1(L: LuaState?, idx: Int, i: Int): COpaquePointer1? =
        md._lua_getupvalue(
            L?.value ?: 0,
            idx, i,
        ).unsafeCast<Int>().ptr

    actual fun lua_tocfunction1(L: LuaState?, idx: Int): lua_CFunction1? =
        md._lua_tocfunction(
            L?.value ?: 0,
            idx,
        ).unsafeCast<Int>().ifNonZero?.let { lua_CFunction1(it) }

    actual fun lua_next1(L: LuaState?, idx: Int): Int = md._lua_next(L?.value ?: 0, idx)
    actual fun lua_getmetatable1(L: LuaState?, idx: Int): Int = md._lua_getmetatable(
        L?.value ?: 0,
        idx,
    )
    actual fun lua_touserdata1(L: LuaState?, idx: Int): COpaquePointer1? =
        md._lua_touserdata(L?.value ?: 0, idx).unsafeCast<Int>().ptr

    actual fun lua_gettable1(L: LuaState?, idx: Int): Int = md._lua_gettable(
        L?.value ?: 0,
        idx,
    )

    actual fun lua_rawget1(L: LuaState?, idx: Int): Int = md._lua_rawget(
        L?.value ?: 0,
        idx,
    )

    actual fun lua_rawset1(L: LuaState?, idx: Int): Unit = md._lua_rawset(
        L?.value ?: 0,
        idx,
    )

    actual fun lua_len1(L: LuaState?, idx: Int): Unit = md._lua_len(
        L?.value ?: 0,
        idx,
    )

    actual fun lua_rawlen1(L: LuaState?, idx: Int): Int = md._lua_rawlen(
        L?.value ?: 0,
        idx,
    )

    actual fun lua_typename1(L: LuaState?, t: Int): String {
        val ptr = md._lua_typename(
            L?.value ?: 0,
            t,
        ).unsafeCast<NativePtr1>()
        return readString(ptr)
    }

    fun getClosureGcFuncAddress(): NativePtr1 = md._klua_get_closureGc_func().unsafeCast<Int>()
    fun getUserdataGcFuncAddress(): NativePtr1 = md._klua_get_userdataGc_func().unsafeCast<Int>()
    val maxstack
        get() = md._klua_get_LUAI_MAXSTACK().unsafeCast<Int>()
}

internal val WASM_INSTANCE: LuaWasm
    get() = luaModule ?: throw Error("Make sure you loaded lua wasm library")


internal actual val LUALIB_INSTANCE: LuaLib by lazy {
    LuaLib(WASM_INSTANCE)
}

private val Int.ptr
    get() = ifNonZero?.let { COpaquePointer1(it) }

private val Int.ifNonZero
    get() = takeIf { it != 0 }

external class TextDecoder {
    fun decode(b: Int8Array): String
}

external class TextEncoder {
    fun encode(string: String): Uint8Array
}