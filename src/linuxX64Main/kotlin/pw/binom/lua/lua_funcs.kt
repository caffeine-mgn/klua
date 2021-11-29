package pw.binom.lua

import cnames.structs.lua_State
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import platform.internal_lua.*

typealias LuaState = CPointer<lua_State>

internal inline fun lua_istable(L: LuaState, n: Int) = (lua_type(L, (n)) == LUA_TTABLE)
internal inline fun lua_call(L: LuaState, n: Int, r: Int) = lua_callk(L, (n), (r), 0, null)
internal inline fun lua_pcall(L: LuaState, n: Int, r: Int, f: Int) =
    lua_pcallk(L, (n), (r), (f), 0, null)

internal inline fun lua_upvalueindex(i: Int) = (LUA_REGISTRYINDEX - (i))
internal inline fun lua_pop(L: LuaState, n: Int) = lua_settop(L, -(n) - 1)
internal inline fun lua_newtable(L: LuaState) = lua_createtable(L, 0, 0)
internal inline fun lua_isnil(L: LuaState, n: Int) = (lua_type(L, (n)) == LUA_TNIL)
internal inline fun lua_isfunction(L: LuaState, n: Int) = (lua_type(L, (n)) == LUA_TFUNCTION)
internal inline fun lua_register(L: LuaState, n: String, f: lua_CFunction) {
    lua_pushcfunction(L, (f))
    lua_setglobal(L, n)
}

internal inline fun lua_pushcfunction(L: LuaState, f: lua_CFunction) {
    lua_pushcclosure(L, (f), 0)
}

internal inline fun lua_tostring(L: LuaState, i: Int) = lua_tolstring(L, (i), null)?.toKString()
internal inline fun LuaState.pop(size: Int) = lua_pop(this, size)
internal inline fun lua_newuserdata(L: LuaState, s: Int) = lua_newuserdatauv(L, s.convert(), 1)

internal inline fun <T> LuaState.checkState(func: () -> T): T {
    val top = lua_gettop(this)
    return try {
        func()
    } finally {
        val newTop = lua_gettop(this)
        check(newTop == top) { "Invalid Stack Size. Expected: ${top}, Actual: $newTop" }
    }
}