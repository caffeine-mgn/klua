@file:OptIn(ExperimentalForeignApi::class)

package pw.binom.lua

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.asStableRef

import kotlinx.cinterop.staticCFunction
import platform.internal_lua.*


val CLOSURE_FUNCTION: lua_CFunction = staticCFunction { state ->
    try {
        val ctx = state!!.readLightUserData(lua_upvalueindex1(2))!!.asStableRef<LuaContext>().get()
        val funcPtr = ctx.readValue(lua_upvalueindex1(1), false)
        val value = funcPtr.checkedData()
        val func = value.value<LuaFunction>()
        val count = lua_gettop(state)
        val args = (2..count).map {
            ctx.readValue(it, true)
        }
        lua_pop(state, count)
        val result = func.call(
            args = args,
        )
        result.forEach {
            ctx.pushValue(it)
        }
        result.size
    } catch (e: Throwable) {
        luaL_error(state, e.stackTraceToString())
        0
    }
}

val closureGc: lua_CFunction1 = staticCFunction { state ->
    try {
        val data = state!!.readUserData(-1)
        data?.asStableRef<Any>()?.dispose()
        0
    } catch (e: Throwable) {
        luaL_error(state, "Can't destroy closure: ${e.stackTraceToString()}")
        0
    }
}

val userdataGc: lua_CFunction = staticCFunction { state ->
    val llptr = state!!.readLightUserData(lua_upvalueindex1(1))!!.asStableRef<LuaContext>()
    try {
        check(lua_gettop(state) == 1) { "Invalid arguments" }
        val ll = llptr.get()
        val userData = ll.readValue(-1, false).checkedUserdata()
        userData.dispose()
        0
    } catch (e: Throwable) {
        luaL_error(state, "Can't destroy userdata: $e")
        0
    }
}
