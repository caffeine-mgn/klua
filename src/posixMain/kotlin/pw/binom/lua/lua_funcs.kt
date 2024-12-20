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
internal inline fun lua_remove1(L: LuaState, idx: Int) {
    lua_rotate(L, (idx), -1)
    lua_pop(L, 1)
}

internal inline fun LuaStateAndLib.pop(size: Int) = lua_pop(state, size)
internal inline fun LuaLib.lua_newuserdata1(L: LuaState, s: Int) = lua_newuserdatauv(L, s.convert(), 1)
internal inline fun <T> LuaStateAndLib.checkState(func: () -> T): T {
    val top = lua_gettop(state)
    return try {
        func()
    } finally {
        val newTop = lua_gettop(state)
        check(newTop == top) { "Invalid Stack Size. Expected: $top, Actual: $newTop" }
    }
}

internal inline fun COpaquePointer?.strPtr() = this?.toLong()?.toString(16) ?: "0"

internal fun LuaStateAndLib.absoluteStackValue(index: Int) =
    if (index.absoluteValue <= 255 && index < 0) lua_gettop(state) + index + 1 else index

internal inline fun LuaStateAndLib.makeRef(): LuaRef = LuaRef(luaL_ref(state, LUA_REGISTRYINDEX))

internal fun LuaStateAndLib.makeRef(popValue: Boolean): LuaRef =
    if (popValue) {
        makeRef()
    } else {
        lua_pushvalue(state, -1)
        LuaRef(luaL_ref(state, LUA_REGISTRYINDEX))
    }

internal fun LuaStateAndLib.makeRef(index: Int, popValue: Boolean): LuaRef =
    if (lua_gettop(state) != index) {
        lua_pushvalue(state, index)
        val ref = makeRef()
        if (popValue) {
            lua_remove1(state, index)
        }
        ref
    } else {
        makeRef(popValue)
    }

internal inline fun LuaStateAndLib.disposeRef(ref: LuaRef) = luaL_unref(state, LUA_REGISTRYINDEX, ref.id)
internal inline fun LuaStateAndLib.pushRef(ref: LuaRef) = lua_rawgeti(state, LUA_REGISTRYINDEX, ref.id.convert())
internal inline fun LuaStateAndLib.type(index: Int = -1) = lua_type(state, index)
// internal inline fun LuaState.typeName(index: Int = -1) = lua_typename1(this, type(index))

value class LuaRef(val id: Int)

// -----------------------------//

internal inline fun lua_istable(L: LuaState, n: Int) = (lua_type(L, (n)) == LUA_TTABLE)
internal inline fun lua_call(L: LuaState, n: Int, r: Int) = lua_callk(L, (n), (r), 0, null)

// internal inline fun lua_newtable(L: LuaState) = lua_createtable(L, 0, 0)

// internal inline fun lua_register(L: LuaState, n: String, f: lua_CFunction1) {
//    lua_pushcfunction(L, (f))
//    lua_setglobal(L, n)
// }

// internal inline fun lua_replace(L: LuaState, idx: Int) {
//    lua_copy(L, -1, (idx))
//    lua_pop(L, 1)
// }

// internal inline fun lua_pushcfunction(L: LuaState, f: lua_CFunction1) {
//    lua_pushcclosure(L, (f), 0)
// }

internal inline fun lua_tostring(L: LuaState, i: Int) = lua_tolstring(L, (i), null)?.toKString()
