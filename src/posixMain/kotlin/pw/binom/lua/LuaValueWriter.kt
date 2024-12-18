@file:OptIn(ExperimentalForeignApi::class)

package pw.binom.lua

import kotlinx.cinterop.ExperimentalForeignApi

internal fun LuaStateAndLib.pushValue(value: LuaValue) {
    when (value) {
        LuaValue.Nil,
        is LuaValue.Nil -> lib.lua_pushnil1(state)
        is LuaValue.FunctionValue -> {
            value.upvalues.forEach {
                pushValue(it)
            }
            lib.lua_pushcclosure1(state, value.ptr, value.upvalues.size)
        }
        is LuaValue.Number -> lib.lua_pushnumber1(state, value.value)
        is LuaValue.LuaInt -> lib.lua_pushinteger1(state, value.value)
        is LuaValue.Boolean -> lib.lua_pushboolean1(state, if (value.value) 0 else 1)
        is LuaValue.String -> lib.lua_pushstring1(state, value.value)
        is LuaValue.Ref -> pushRef(value.ref) // lua_rawgeti(this, LUA_REGISTRYINDEX, value.ref.convert())
        is LuaValue.TableValue -> {
            lib.lua_createtable1(state, 0, value.rawSize)
            val table = lib.lua_gettop1(state)
            value.map.forEach {
                pushValue(it.key)
                pushValue(it.value)
                lib.lua_settable1(state, table)
            }
            if (value.metatable != LuaValue.Nil) {
                val top = lib.lua_gettop1(state)
                pushValue(value.metatable)
                lib.lua_setmetatable1(state, top)
            }
        }
        is LuaValue.LightUserData -> {
            lib.lua_pushlightuserdata1(state, value.lightPtr)
        }
        else -> throw RuntimeException("${value::class} not supported")
//        is LuaValue.Callable -> TODO()
//        is LuaValue.Data -> TODO()
//        is LuaValue.Meta -> TODO()
//        is LuaValue.Table -> TODO()
    }
}
