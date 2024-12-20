@file:OptIn(ExperimentalForeignApi::class)

package pw.binom.lua

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef

import kotlinx.cinterop.staticCFunction
import platform.internal_lua.LUA_REGISTRYINDEX
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
        val ll = LuaStateAndLib(state!!)
        StdOut.info("Try to call js function LUA_REGISTRYINDEX1=$LUA_REGISTRYINDEX")
        val funcPtr = ll.readValue(lua_upvalueindex1(1), false)
        StdOut.info("funcPtr=$funcPtr")
        val value = funcPtr.checkedData()
        val func = value.value<LuaFunction>()
        val count = lua_gettop(state)
        val args = (1..count).mapNotNull {
            val arg = ll.readValue(it, true)
            if (arg is LuaValue.UserData && arg.ptr == AC_CLOSURE_PTR) {
                return@mapNotNull null
            }
            arg
        }
        lua_pop(state, count)
        val result = func.call(
            req = args,
        )
        result.forEach {
            ll.pushValue(it)
        }
        result.size
    } catch (e: Throwable) {
        e.printStackTrace()
        luaL_error(state, e.toString())
        0
    }
}

val closureGc: lua_CFunction1 = staticCFunction<LuaState?, Int> { state ->
    check(lua_gettop(state) == 1) { "Invalid arguments" }
    val llptr = state!!.readLightUserData(lua_upvalueindex1(1))!!.asStableRef<LuaStateAndLib>()
    try {
        val ll = llptr.get()
        val userData = ll.readValue(-1, false).checkedUserdata()
        val funcValue = userData.metatable.checkedTable()["__call".lua].checkedFunctionRef().toValue()
        check(funcValue.upValues.size == 1) { "Invalid upvalues state" }
        funcValue.upValues[0].checkedLightUserdata().dispose()
        0
    } catch (e: Throwable) {
        luaL_error(state, "Can't destroy closure: $e")
        0
    }

}

val userdataGc: lua_CFunction = staticCFunction { state ->
    val llptr = state!!.readLightUserData(lua_upvalueindex1(1))!!.asStableRef<LuaStateAndLib>()
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
