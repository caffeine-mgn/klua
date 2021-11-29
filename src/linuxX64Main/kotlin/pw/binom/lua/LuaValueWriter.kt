package pw.binom.lua

import platform.internal_lua.*

fun LuaState.pushValue(value: LuaValue) {
    when (value) {
        is LuaValue.Nil -> lua_pushnil(this)
        is LuaValue.FunctionValue -> {
            value.upvalues.forEach {
                pushValue(it)
            }
            lua_pushcclosure(this, value.ptr, value.upvalues.size)
        }
        is LuaValue.Number -> lua_pushnumber(this, value.value)
        is LuaValue.LuaInt -> lua_pushinteger(this, value.value)
        is LuaValue.Boolean -> lua_pushboolean(this, if (value.value) 0 else 1)
        is LuaValue.String -> lua_pushstring(this, value.value)
        is LuaValue.Ref -> klua_push_value(this, value.ref)//lua_rawgeti(this, LUA_REGISTRYINDEX, value.ref.convert())
        is LuaValue.TableValue -> {
            lua_createtable(this, 0, value.rawSize)
            val table = lua_gettop(this)
            value.map.forEach {
                pushValue(it.key)
                pushValue(it.value)
                lua_settable(this, table);
            }
            if (value.metatable != LuaValue.Nil) {
                val top = lua_gettop(this)
                pushValue(value.metatable)
                lua_setmetatable(this, top)
            }
        }
        is LuaValue.LightUserData -> {
            lua_pushlightuserdata(this, value.lightPtr)
        }
    }
}