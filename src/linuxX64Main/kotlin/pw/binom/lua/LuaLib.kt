package pw.binom.lua

import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import platform.internal_lua.*

internal actual class LuaLib {
    actual val heap = Heap()
    actual fun luaL_newstate1(): LuaState? = luaL_newstate()
    actual fun luaL_openlibs1(state: LuaState) = luaL_openlibs(state)
    actual fun lua_getglobal1(state: LuaState, name: String) = lua_getglobal(state, name)
    actual fun lua_close1(state: LuaState) = lua_close(state)
    actual fun lua_setglobal1(state: LuaState, name: String) = lua_setglobal(state, name)
    actual fun lua_gettop1(state: LuaState) = lua_gettop(state)
    actual fun lua_tostring1(state: LuaState, i: Int) = lua_tostring(state, i)
    actual fun luaL_tolstring1(state: LuaState, i: Int) = luaL_tolstring(state, i,null)?.toKString()
    actual fun luaL_ref1(state: LuaState, t: Int) = luaL_ref(state, t)
    actual fun luaL_unref1(state: LuaState, t: Int, ref: Int) = luaL_unref(state, t, ref)
    actual fun lua_isstring1(state: LuaState, idx: Int) = lua_isstring(state, idx)
    actual fun lua_pushvalue1(state: LuaState, idx: Int) = lua_pushvalue(state, idx)
    actual fun lua_type1(state: LuaState, idx: Int): Int = lua_type(state, idx)
    actual fun lua_rawgeti1(state: LuaState, t: Int, ref: Int): Int = lua_rawgeti(state, t, ref.convert())
    actual fun lua_rotate1(state: LuaState, idx: Int, n: Int) = lua_rotate(state, idx, n)
    actual fun lua_newuserdatauv1(state: LuaState, sz: Int, nuvalue: Int): COpaquePointer1? =
        lua_newuserdatauv(state, sz.convert(), nuvalue)

    actual fun lua_settop1(state: LuaState, idx: Int) = lua_settop(state, idx)
    actual fun lua_pcallk1(state: LuaState, a: Int, b: Int, c: Int, d: lua_KContext1, e: lua_CFunction1?) =
        lua_pcallk(state, a, b, c, d, e?.reinterpret())

    actual fun lua_topointer1(L: LuaState?, idx: Int): COpaquePointer1? = lua_topointer(L, idx)
    actual fun luaL_traceback1(L: LuaState?, L1: LuaState?, msg: String?, level: Int) =
        luaL_traceback(L, L1, msg, level)

    actual fun luaL_loadstring1(L: LuaState?, s: String) = luaL_loadstring(L, s)
    actual fun lua_pushnil1(L: LuaState?) = lua_pushnil(L)
    actual fun lua_pushcclosure1(L: LuaState?, fn: lua_CFunction1?, n: Int) = lua_pushcclosure(L, fn, n)
    actual fun lua_createtable1(L: LuaState?, narr: Int, nrec: Int) = lua_createtable(L, narr, nrec)
    actual fun lua_pushnumber1(L: LuaState?, n: Double) = lua_pushnumber(L, n)
    actual fun lua_pushinteger1(L: LuaState?, n: Long) = lua_pushinteger(L, n)
    actual fun lua_pushboolean1(L: LuaState?, n: Int) = lua_pushboolean(L, n)
    actual fun lua_pushstring1(L: LuaState?, n: String) {
        lua_pushstring(L, n)
    }

    actual fun lua_settable1(L: LuaState?, idx: Int) = lua_settable(L, idx)
    actual fun lua_setmetatable1(L: LuaState?, idx: Int) = lua_setmetatable(L, idx)
    actual fun lua_pushlightuserdata1(L: LuaState?, idx: COpaquePointer1?) = lua_pushlightuserdata(L, idx)
    actual fun lua_tonumberx1(L: LuaState?, idx: Int): Double = lua_tonumberx(L, idx, null)
    actual fun lua_toboolean1(L: LuaState?, idx: Int) = lua_toboolean(L, idx)
    actual fun lua_getupvalue1(L: LuaState?, idx: Int, i: Int): COpaquePointer1? = lua_getupvalue(L, idx, i)
    actual fun lua_tocfunction1(L: LuaState?, idx: Int) = lua_tocfunction(L, idx)
    actual fun lua_next1(L: LuaState?, idx: Int) = lua_next(L, idx)
    actual fun lua_getmetatable1(L: LuaState?, idx: Int) = lua_getmetatable(L, idx)
    actual fun lua_touserdata1(L: LuaState?, idx: Int) = lua_touserdata(L, idx)
    actual fun lua_gettable1(L: LuaState?, idx: Int) = lua_gettable(L, idx)
    actual fun lua_rawget1(L: LuaState?, idx: Int) = lua_rawget(L, idx)
    actual fun lua_rawset1(L: LuaState?, idx: Int) = lua_rawset(L, idx)
    actual fun lua_len1(L: LuaState?, idx: Int) = lua_len(L, idx)
    actual fun lua_rawlen1(L: LuaState?, idx: Int): Int = lua_rawlen(L, idx).toInt()
    actual fun lua_typename1(L: LuaState?, t: Int) = lua_typename(L, t)?.toKString() ?: ""
}

private val instance = LuaLib()
internal actual val LUALIB_INSTANCE: LuaLib
    get() = instance