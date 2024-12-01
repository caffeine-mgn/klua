package pw.binom.lua

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import platform.internal_lua.*
import kotlinx.cinterop.*
/*
internal expect class LuaLib {
    val heap: Heap
    fun luaL_newstate1(): LuaState?
    fun luaL_openlibs1(state: LuaState)
    fun lua_getglobal1(state: LuaState, name: String): Int
    fun lua_setglobal1(state: LuaState, name: String)
    fun lua_close1(state: LuaState)
    fun lua_gettop1(state: LuaState): Int
    fun lua_tostring1(state: LuaState, i: Int): String?
    fun luaL_tolstring1(state: LuaState, i: Int): String?
    fun luaL_ref1(state: LuaState, t: Int): Int
    fun luaL_unref1(state: LuaState, t: Int, ref: Int)
    fun lua_isstring1(state: LuaState, idx: Int): Int
    fun lua_pushvalue1(state: LuaState, idx: Int)
    fun lua_type1(state: LuaState, idx: Int): Int
    fun lua_rawgeti1(state: LuaState, t: Int, ref: Int): Int
    fun lua_rotate1(state: LuaState, idx: Int, n: Int)
    fun lua_newuserdatauv1(state: LuaState, sz: Int, nuvalue: Int): COpaquePointer1?
    fun lua_settop1(state: LuaState, idx: Int)
    fun lua_pcallk1(
        state: LuaState,
        nargs: Int,
        nresults: Int,
        errfunc: Int,
        ctx: lua_KContext1,
        k: lua_CFunction1?
    ): Int

    fun lua_topointer1(L: LuaState?, idx: Int): COpaquePointer1?
    fun luaL_traceback1(L: LuaState?, L1: LuaState?, msg: String?, level: Int)
    fun luaL_loadstring1(L: LuaState?, s: String): Int
    fun lua_pushnil1(L: LuaState?)
    fun luaL_error1(L: LuaState?, message: String?): Int
    fun lua_pushcclosure1(L: LuaState?, fn: lua_CFunction1?, n: Int)
    fun lua_createtable1(L: LuaState?, narr: Int, nrec: Int)
    fun lua_pushnumber1(L: LuaState?, n: Double)
    fun lua_pushinteger1(L: LuaState?, n: Long)
    fun lua_pushboolean1(L: LuaState?, n: Int)
    fun lua_pushstring1(L: LuaState?, n: String)
    fun lua_settable1(L: LuaState?, idx: Int)
    fun lua_setmetatable1(L: LuaState?, idx: Int): Int
    fun lua_pushlightuserdata1(L: LuaState?, idx: COpaquePointer1?)
    fun lua_tonumberx1(L: LuaState?, idx: Int): Double
    fun lua_toboolean1(L: LuaState?, idx: Int): Int
    fun lua_getupvalue1(L: LuaState?, idx: Int, i: Int): COpaquePointer1?
    fun lua_tocfunction1(L: LuaState?, idx: Int): lua_CFunction1?
    fun lua_next1(L: LuaState?, idx: Int): Int
    fun lua_getmetatable1(L: LuaState?, idx: Int): Int
    fun lua_touserdata1(L: LuaState?, idx: Int): COpaquePointer1?
    fun lua_gettable1(L: LuaState?, idx: Int): Int
    fun lua_rawget1(L: LuaState?, idx: Int): Int
    fun lua_rawset1(L: LuaState?, idx: Int)
    fun lua_len1(L: LuaState?, idx: Int)
    fun lua_rawlen1(L: LuaState?, idx: Int): Int
    fun lua_typename1(L: LuaState?, t: Int): String
}

internal expect val LUALIB_INSTANCE: LuaLib
*/
// ------------------------------------- //

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal class LuaLib {
     val heap = Heap()
     fun luaL_newstate1(): LuaState? = luaL_newstate()
     fun luaL_openlibs1(state: LuaState) = luaL_openlibs(state)
     fun lua_getglobal1(state: LuaState, name: String) = lua_getglobal(state, name)
     fun lua_close1(state: LuaState) = lua_close(state)
     fun lua_setglobal1(state: LuaState, name: String) = lua_setglobal(state, name)
     fun lua_gettop1(state: LuaState) = lua_gettop(state)
     fun lua_tostring1(state: LuaState, i: Int) = lua_tostring(state, i)
     fun luaL_tolstring1(state: LuaState, i: Int) = luaL_tolstring(state, i, null)?.toKString()
     fun luaL_ref1(state: LuaState, t: Int) = luaL_ref(state, t)
     fun luaL_unref1(state: LuaState, t: Int, ref: Int) = luaL_unref(state, t, ref)
     fun lua_isstring1(state: LuaState, idx: Int) = lua_isstring(state, idx)
     fun lua_pushvalue1(state: LuaState, idx: Int) = lua_pushvalue(state, idx)
     fun lua_type1(state: LuaState, idx: Int): Int = lua_type(state, idx)
     fun lua_rawgeti1(state: LuaState, t: Int, ref: Int): Int = lua_rawgeti(state, t, ref.convert())
     fun lua_rotate1(state: LuaState, idx: Int, n: Int) = lua_rotate(state, idx, n)
     fun lua_newuserdatauv1(state: LuaState, sz: Int, nuvalue: Int): COpaquePointer? =
        lua_newuserdatauv(state, sz.convert(), nuvalue)

     fun lua_settop1(state: LuaState, idx: Int) = lua_settop(state, idx)
     fun lua_pcallk1(
        state: LuaState,
        nargs: Int,
        nresults: Int,
        errfunc: Int,
        ctx: lua_KContext1,
        k: lua_CFunction1?
    ) =
        lua_pcallk(state, nargs, nresults, errfunc, ctx.convert(), k?.reinterpret())

     fun lua_topointer1(L: LuaState?, idx: Int): COpaquePointer? = lua_topointer(L, idx)
     fun luaL_traceback1(L: LuaState?, L1: LuaState?, msg: String?, level: Int) =
        luaL_traceback(L, L1, msg, level)

     fun luaL_loadstring1(L: LuaState?, s: String) = luaL_loadstring(L, s)
     fun lua_pushnil1(L: LuaState?) = lua_pushnil(L)
     fun luaL_error1(L: LuaState?, message: String?) = luaL_error(L, message)
     fun lua_pushcclosure1(L: LuaState?, fn: lua_CFunction1?, n: Int) = lua_pushcclosure(L, fn, n)
     fun lua_createtable1(L: LuaState?, narr: Int, nrec: Int) = lua_createtable(L, narr, nrec)
     fun lua_pushnumber1(L: LuaState?, n: Double) = lua_pushnumber(L, n)
     fun lua_pushinteger1(L: LuaState?, n: Long) = lua_pushinteger(L, n)
     fun lua_pushboolean1(L: LuaState?, n: Int) = lua_pushboolean(L, n)
     fun lua_pushstring1(L: LuaState?, n: String) {
        lua_pushstring(L, n)
    }

     fun lua_settable1(L: LuaState?, idx: Int) = lua_settable(L, idx)
     fun lua_setmetatable1(L: LuaState?, idx: Int) = lua_setmetatable(L, idx)
     fun lua_pushlightuserdata1(L: LuaState?, idx: COpaquePointer?) = lua_pushlightuserdata(L, idx)
     fun lua_tonumberx1(L: LuaState?, idx: Int): Double = lua_tonumberx(L, idx, null)
     fun lua_toboolean1(L: LuaState?, idx: Int) = lua_toboolean(L, idx)
     fun lua_getupvalue1(L: LuaState?, idx: Int, i: Int): COpaquePointer? = lua_getupvalue(L, idx, i)
     fun lua_tocfunction1(L: LuaState?, idx: Int) = lua_tocfunction(L, idx)
     fun lua_next1(L: LuaState?, idx: Int) = lua_next(L, idx)
     fun lua_getmetatable1(L: LuaState?, idx: Int) = lua_getmetatable(L, idx)
     fun lua_touserdata1(L: LuaState?, idx: Int) = lua_touserdata(L, idx)
     fun lua_gettable1(L: LuaState?, idx: Int) = lua_gettable(L, idx)
     fun lua_rawget1(L: LuaState?, idx: Int) = lua_rawget(L, idx)
     fun lua_rawset1(L: LuaState?, idx: Int) = lua_rawset(L, idx)
     fun lua_len1(L: LuaState?, idx: Int) = lua_len(L, idx)
     fun lua_rawlen1(L: LuaState?, idx: Int): Int = lua_rawlen(L, idx).toInt()
     fun lua_typename1(L: LuaState?, t: Int) = lua_typename(L, t)?.toKString() ?: ""
}

private val instance = LuaLib()
internal  val LUALIB_INSTANCE: LuaLib
    get() = instance
