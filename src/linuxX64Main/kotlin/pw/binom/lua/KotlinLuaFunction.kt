package pw.binom.lua

import kotlinx.cinterop.staticCFunction
import platform.internal_lua.luaL_error
import platform.internal_lua.lua_gettop

actual val AC_CLOSURE_PTR = staticCFunction<Unit> {
//Do nothing
    0
}

private fun callClosure(skipClosureUserData: Boolean, state: LuaState): Int {
    val ll = LuaStateAndLib(state,LuaLib.NATIVE)
    val value = ll.readValue(LuaLib.NATIVE.lua_upvalueindex1(1), false).checkedData()
    val func = value.value<LuaFunction>()
    val count = lua_gettop(state)
    val args = (1..count).mapNotNull {
        val arg = ll.readValue(it, true)
        if (arg is LuaValue.UserData && arg.ptr == AC_CLOSURE_PTR)
            return@mapNotNull null
        arg
    }
    LuaLib.NATIVE.lua_pop1(state, count)
    val result = func.call(
        req = args,
    )
    result.forEach {
        ll.pushValue(it)
    }
    return result.size
}

//val AC_CLOSURE_FUNCTION = staticCFunction<LuaState?, Int> { state ->
//    callClosure(true, state!!)
//}

actual val CLOSURE_FUNCTION = staticCFunction<LuaState?, Int> { state ->
    try {
        callClosure(true, state!!)
    } catch (e: Throwable) {
        e.printStackTrace()
        luaL_error(state, e.toString())
        0
    }
}

actual val closureGc = staticCFunction<LuaState?, Int> { state ->
    try {
        check(lua_gettop(state) == 1) { "Invalid arguments" }
        val ll = LuaStateAndLib(state!!,LuaLib.NATIVE)
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

actual val userdataGc = staticCFunction<LuaState?, Int> { state ->
    try {
        check(lua_gettop(state) == 1) { "Invalid arguments" }
        val ll = LuaStateAndLib(state!!,LuaLib.NATIVE)
        val userData = ll.readValue(-1, false).checkedUserdata()
        userData.dispose()
        0
    } catch (e: Throwable) {
        luaL_error(state, "Can't destroy userdata: $e")
        0
    }
}
