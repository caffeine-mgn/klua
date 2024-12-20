@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package pw.binom.lua

import kotlinx.cinterop.*
import platform.internal_lua.*
import kotlin.math.absoluteValue

internal inline fun lua_pcall1(L: LuaState, n: Int, r: Int, f: Int) =
    lua_pcallk(L, (n), (r), (f), 0.convert(), (null as lua_CFunction1?)?.reinterpret())

internal inline fun lua_upvalueindex1(i: Int) = (LUA_REGISTRYINDEX - (i))
internal inline fun lua_pop(L: LuaState, n: Int) = lua_settop(L, -(n) - 1)
//internal inline fun LuaLib.lua_pop1(L: LuaState, n: Int) = lua_settop(L, -(n) - 1)
internal inline fun lua_isnil1(L: LuaState, n: Int) = (lua_type(L, (n)) == LUA_TNIL)
internal inline fun lua_isfunction1(L: LuaState, n: Int) = (lua_type(L, (n)) == LUA_TFUNCTION)
internal inline fun lua_remove(L: LuaState, idx: Int) {
    lua_rotate(L, (idx), -1)
    lua_pop(L, 1)
}

internal inline fun lua_newuserdata1(L: LuaState, s: Int) = lua_newuserdatauv(L, s.convert(), 1)
internal inline fun <T> LuaState.checkState(func: () -> T): T {
    val top = lua_gettop(this)
    return try {
        func()
    } finally {
        val newTop = lua_gettop(this)
        check(newTop == top) { "Invalid Stack Size. Expected: $top, Actual: $newTop" }
    }
}

internal inline fun COpaquePointer?.strPtr() = this?.toLong()?.toString(16) ?: "0"

internal fun LuaState.absoluteStackValue(index: Int) =
    if (index.absoluteValue <= 255 && index < 0) lua_gettop(this) + index + 1 else index

internal inline fun LuaState.makeRef(): LuaRef = LuaRef(luaL_ref(this, LUA_REGISTRYINDEX))

internal fun LuaState.makeRef(popValue: Boolean): LuaRef =
    if (popValue) {
        makeRef()
    } else {
        lua_pushvalue(this, -1)
        LuaRef(luaL_ref(this, LUA_REGISTRYINDEX))
    }

internal fun LuaState.makeRef(index: Int, popValue: Boolean): LuaRef =
    if (lua_gettop(this) != index) {
        lua_pushvalue(this, index)
        val ref = this.makeRef()
        if (popValue) {
            lua_remove(this, index)
        }
        ref
    } else {
        this.makeRef(popValue)
    }

internal inline fun LuaState.disposeRef(ref: LuaRef) = luaL_unref(this, LUA_REGISTRYINDEX, ref.id)
internal inline fun LuaState.pushRef(ref: LuaRef) = lua_rawgeti(this, LUA_REGISTRYINDEX, ref.id.convert())

value class LuaRef(val id: Int)

internal inline fun lua_tostring(L: LuaState, i: Int) = lua_tolstring(L, (i), null)?.toKString()
