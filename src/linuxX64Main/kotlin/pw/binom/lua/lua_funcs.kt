package pw.binom.lua

import cnames.structs.lua_State
import kotlinx.cinterop.*
import platform.internal_lua.*
import kotlin.math.absoluteValue

internal inline fun lua_istable(L: LuaState, n: Int) = (lua_type(L, (n)) == LUA_TTABLE)
internal inline fun lua_call(L: LuaState, n: Int, r: Int) = lua_callk(L, (n), (r), 0, null)






//internal inline fun lua_newtable(L: LuaState) = lua_createtable(L, 0, 0)

//internal inline fun lua_register(L: LuaState, n: String, f: lua_CFunction1) {
//    lua_pushcfunction(L, (f))
//    lua_setglobal(L, n)
//}



//internal inline fun lua_replace(L: LuaState, idx: Int) {
//    lua_copy(L, -1, (idx))
//    lua_pop(L, 1)
//}

//internal inline fun lua_pushcfunction(L: LuaState, f: lua_CFunction1) {
//    lua_pushcclosure(L, (f), 0)
//}

internal inline fun lua_tostring(L: LuaState, i: Int) = lua_tolstring(L, (i), null)?.toKString()








