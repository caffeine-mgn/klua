package pw.binom.lua

internal fun callClosure(state: LuaState): Int {
    state.printStack("Call Closure Args")
    val ll = LuaStateAndLib(state, LUALIB_INSTANCE)
    StdOut.info("Try to call js function LUA_REGISTRYINDEX1=$LUA_REGISTRYINDEX1")
    val funcPtr = ll.readValue(LUALIB_INSTANCE.lua_upvalueindex1(1), false)
    StdOut.info("funcPtr=$funcPtr")
    val value = funcPtr.checkedData()
    val func = value.value<LuaFunction>()
    val count = LUALIB_INSTANCE.lua_gettop1(state)
    val args = (1..count).mapNotNull {
        val arg = ll.readValue(it, true)
        if (arg is LuaValue.UserData && arg.ptr == AC_CLOSURE_PTR) {
            return@mapNotNull null
        }
        arg
    }
    LUALIB_INSTANCE.lua_pop1(state, count)
    val result = func.call(
        req = args,
    )
    result.forEach {
        ll.pushValue(it)
    }
    return result.size
}
