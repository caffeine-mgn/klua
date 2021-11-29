package pw.binom.lua

import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import platform.internal_lua.luaL_error
import platform.internal_lua.lua_gettop

val userFunction = staticCFunction<LuaState?, Int> { state ->
    try {
        val value = state!!.readValue(lua_upvalueindex(1), false).checkedUserdata()
        val func = value.lightPtr!!.asStableRef<LuaFunction>().get()
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
        e.printStackTrace()
        luaL_error(state, e.message ?: "")
        0
    }
}