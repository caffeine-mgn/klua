@file:OptIn(ExperimentalForeignApi::class, ExperimentalForeignApi::class)

package pw.binom.lua

import kotlinx.cinterop.ExperimentalForeignApi
import platform.internal_lua.*

internal fun callClosure(state: LuaState): Int {
    state.printStack("Call Closure Args")
    val ll = LuaStateAndLib(state)
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
    return result.size
}
