@file:OptIn(ExperimentalForeignApi::class)

package pw.binom.lua

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import platform.internal_lua.*

/*
 * Native (POSIX/Kotlin-Native) bridge for [LuaLibrary]. Each standard library
 * is exposed by Lua's C API as a `lua_CFunction` (luaopen_*), but cinterop
 * presents them as Kotlin functions, so we wrap each in a `staticCFunction`
 * to obtain the raw C function pointer required by `luaL_requiref`.
 *
 * This mirrors the JNI side (`openLibsMask` in klua_jni.c) and keeps the
 * engine's library-selection semantics identical across all targets.
 */

private val openerBase: lua_CFunction = staticCFunction { s -> luaopen_base(s) }
private val openerPackage: lua_CFunction = staticCFunction { s -> luaopen_package(s) }
private val openerCoroutine: lua_CFunction = staticCFunction { s -> luaopen_coroutine(s) }
private val openerTable: lua_CFunction = staticCFunction { s -> luaopen_table(s) }
private val openerIo: lua_CFunction = staticCFunction { s -> luaopen_io(s) }
private val openerOs: lua_CFunction = staticCFunction { s -> luaopen_os(s) }
private val openerString: lua_CFunction = staticCFunction { s -> luaopen_string(s) }
private val openerMath: lua_CFunction = staticCFunction { s -> luaopen_math(s) }
private val openerUtf8: lua_CFunction = staticCFunction { s -> luaopen_utf8(s) }
private val openerDebug: lua_CFunction = staticCFunction { s -> luaopen_debug(s) }

private fun LuaLibrary.opener(): lua_CFunction = when (this) {
    LuaLibrary.BASE -> openerBase
    LuaLibrary.PACKAGE -> openerPackage
    LuaLibrary.COROUTINE -> openerCoroutine
    LuaLibrary.TABLE -> openerTable
    LuaLibrary.IO -> openerIo
    LuaLibrary.OS -> openerOs
    LuaLibrary.STRING -> openerString
    LuaLibrary.MATH -> openerMath
    LuaLibrary.UTF8 -> openerUtf8
    LuaLibrary.DEBUG -> openerDebug
}

internal fun LuaState.openLibs(libraries: Set<LuaLibrary>) {
    libraries.forEach { lib ->
        luaL_requiref(this, lib.luaName, lib.opener(), 1)
        lua_pop(this, 1)
    }
}
