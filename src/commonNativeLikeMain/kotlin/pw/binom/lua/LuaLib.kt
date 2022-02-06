package pw.binom.lua

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
