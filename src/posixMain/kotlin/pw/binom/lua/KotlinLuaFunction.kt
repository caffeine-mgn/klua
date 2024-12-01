@file:OptIn(ExperimentalForeignApi::class)

package pw.binom.lua

import kotlinx.cinterop.ExperimentalForeignApi

import kotlinx.cinterop.staticCFunction
import platform.internal_lua.luaL_error
import platform.internal_lua.lua_CFunction
import platform.internal_lua.lua_gettop

val AC_CLOSURE_PTR = staticCFunction<Unit> {
// Do nothing
    0
}

// val AC_CLOSURE_FUNCTION = staticCFunction<LuaState?, Int> { state ->
//    callClosure(true, state!!)
// }

val CLOSURE_FUNCTION: lua_CFunction = staticCFunction { state ->
    try {
        callClosure(state!!)
    } catch (e: Throwable) {
        e.printStackTrace()
        luaL_error(state, e.toString())
        0
    }
}

val closureGc:lua_CFunction1 = staticCFunction<LuaState?, Int> { state ->
    try {
        check(lua_gettop(state) == 1) { "Invalid arguments" }
        val ll = LuaStateAndLib(state!!, LUALIB_INSTANCE)
        val userData = ll.readValue(-1, false).checkedUserdata()
        val funcValue = userData.metatable.checkedTable()["__call".lua].checkedFunctionRef().toValue()
        check(funcValue.upvalues.size == 1) { "Invalid upvalues state" }
        funcValue.upvalues[0].checkedLightUserdata().dispose()
        0
    } catch (e: Throwable) {
        luaL_error(state, "Can't destroy closure: $e")
        0
    }
}

val userdataGc:lua_CFunction = staticCFunction { state ->
    try {
        check(lua_gettop(state) == 1) { "Invalid arguments" }
        val ll = LuaStateAndLib(state!!, LUALIB_INSTANCE)
        val userData = ll.readValue(-1, false).checkedUserdata()
        userData.dispose()
        0
    } catch (e: Throwable) {
        luaL_error(state, "Can't destroy userdata: $e")
        0
    }
}
