@file:OptIn(ExperimentalForeignApi::class)

package pw.binom.lua

import kotlinx.cinterop.ExperimentalForeignApi
import platform.internal_lua.*

internal fun LuaStateAndLib.pushValue(value: LuaValue) {
    when (value) {
        LuaValue.Nil,
        is LuaValue.Nil -> lua_pushnil(state)
        is LuaValue.FunctionValue -> {
            value.upvalues.forEach {
                pushValue(it)
            }
            lua_pushcclosure(state, value.ptr, value.upvalues.size)
        }
        is LuaValue.Number -> lua_pushnumber(state, value.value)
        is LuaValue.LuaInt -> lua_pushinteger(state, value.value)
        is LuaValue.Boolean -> lua_pushboolean(state, if (value.value) 0 else 1)
        is LuaValue.String -> lua_pushstring(state, value.value)
        is LuaValue.Ref -> state.pushRef(value.ref) // lua_rawgeti(this, LUA_REGISTRYINDEX, value.ref.convert())
        is LuaValue.TableValue -> {
            lua_createtable(state, 0, value.rawSize)
            val table = lua_gettop(state)
            value.map.forEach {
                pushValue(it.key)
                pushValue(it.value)
                lua_settable(state, table)
            }
            if (value.metatable != LuaValue.Nil) {
                val top = lua_gettop(state)
                pushValue(value.metatable)
                lua_setmetatable(state, top)
            }
        }
        is LuaValue.LightUserData -> {
            lua_pushlightuserdata(state, value.lightPtr)
        }
        else -> throw RuntimeException("${value::class} not supported")
//        is LuaValue.Callable -> TODO()
//        is LuaValue.Data -> TODO()
//        is LuaValue.Meta -> TODO()
//        is LuaValue.Table -> TODO()
    }
}
