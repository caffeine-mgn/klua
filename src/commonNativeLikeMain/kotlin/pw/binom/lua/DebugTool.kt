package pw.binom.lua

@Deprecated(message = "Debug Tool", level = DeprecationLevel.WARNING)
actual fun LuaEngine.printStack(message: String?):Unit{
    ll.state.printStack(message)
}


/**
 * Draws stack without any changes
 */
@Deprecated(message = "Debug Tool", level = DeprecationLevel.WARNING)
fun LuaState.printStack(message: String? = null) {
    val msg = message ?: "Lua Stack"
    println("---===$msg===---")
    val count = LUALIB_INSTANCE.lua_gettop1(this)
    for (i in 1..count) {
        val type = LUALIB_INSTANCE.lua_type1(this, i)
        val typename = LUALIB_INSTANCE.lua_typename1(this, type)
        val sb = StringBuilder("$i. type:$typename")
        if (type == LUA_TTABLE1) {
            sb.append(" count:${LUALIB_INSTANCE.lua_rawlen1(this, i)}")
            sb.append(" ptr:${LUALIB_INSTANCE.lua_topointer1(this, i).strPtr()}")
        }
        if (type == LUA_TSTRING1) {
            sb.append(" value:\"${LUALIB_INSTANCE.lua_tostring1(this, i)}\"")
        }
        if (type == LUA_TLIGHTUSERDATA1 || type == LUA_TUSERDATA1) {
            sb.append(" ptr:\"${LUALIB_INSTANCE.lua_touserdata1(this, i).strPtr()}\"")
        }
        println(sb.toString())
    }
    println("---===$msg===---")
}