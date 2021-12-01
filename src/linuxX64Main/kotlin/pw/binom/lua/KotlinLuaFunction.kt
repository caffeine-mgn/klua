package pw.binom.lua

import kotlinx.cinterop.staticCFunction
import platform.internal_lua.luaL_error
import platform.internal_lua.lua_gettop

internal val AC_CLOSURE_PTR = staticCFunction<Unit> {
//Do nothing
}

private fun callClosure(skipClosureUserData: Boolean, state: LuaState): Int {
    val value = state!!.readValue(lua_upvalueindex(1), false).checkedData()
    val func = value.value<LuaFunction>()
    val count = lua_gettop(state)
    val args = (1..count).mapNotNull {
        val arg = state.readValue(it, true)
        if (arg is LuaValue.UserData && arg.ptr == AC_CLOSURE_PTR)
            return@mapNotNull null
        arg
    }
    lua_pop(state, count)
    val result = func.call(
        req = args,
    )
    result.forEach {
        state.pushValue(it)
    }
    return result.size
}

//val AC_CLOSURE_FUNCTION = staticCFunction<LuaState?, Int> { state ->
//    callClosure(true, state!!)
//}

val CLOSURE_FUNCTION = staticCFunction<LuaState?, Int> { state ->
    try {
        callClosure(true, state!!)
    } catch (e: Throwable) {
        e.printStackTrace()
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
