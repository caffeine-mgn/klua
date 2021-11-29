package pw.binom.lua

import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import platform.internal_lua.luaL_error
import platform.internal_lua.lua_gettop

val userFunction = staticCFunction<LuaState?, Int> { state ->
    try {
        val value = state!!.readValue(lua_upvalueindex(1), false).checkedData()
        val func = value.value<LuaFunction>()!!
        val count = lua_gettop(state)
        val args = (1..count).map {
            state.readValue(it, true)
        }
        lua_pop(state, count)
        val result = func.call(
            req = args,
        )
        result.forEach {
            state.pushValue(it)
        }
        result.size
    } catch (e: Throwable) {
        luaL_error(state, e.toString())
        0
    }
}

val closureGc = staticCFunction<LuaState?, Int> { state ->
    try {
        check(lua_gettop(state) == 1) { "Invalid arguments" }
        val userData = state!!.readValue(-1, false).checkedUserdata()
        val funcValue = userData.metatable.checkedTable()["__call".lua].checkedFunctionRef().toValue()
        check(funcValue.upvalues.size == 1) { "Invalid upvalues state" }
        funcValue.upvalues[0].checkedLightUserdata().dispose()
        0
    } catch (e: Throwable) {
        luaL_error(state, "Can't destroy closure: $e")
        0
    }
}

val userdataGc = staticCFunction<LuaState?, Int> { state ->
    try {
        check(lua_gettop(state) == 1) { "Invalid arguments" }
        val userData = state!!.readValue(-1, false).checkedUserdata()
        userData.dispose()
        0
    } catch (e: Throwable) {
        luaL_error(state, "Can't destroy userdata: $e")
        0
    }
}
