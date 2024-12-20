@file:OptIn(ExperimentalForeignApi::class)

package pw.binom.lua

import kotlinx.cinterop.ExperimentalForeignApi
import platform.internal_lua.*

@Deprecated(message = "Debug Tool", level = DeprecationLevel.WARNING)
actual fun LuaEngine.printStack(message: String?) {
    ll.state.printStack(message)
}

/**
 * Draws stack without any changes
 */
@Deprecated(message = "Debug Tool", level = DeprecationLevel.WARNING)
fun LuaState.printStack(message: String? = null) {
    val msg = message ?: "Lua Stack"
    StdOut.info("---===$msg===---")
    val count = lua_gettop(this)
    for (i in 1..count) {
        val type = lua_type(this, i)
        val typename = lua_typename(this, type)?: ""
        val sb = StringBuilder("$i. type:$typename")
        if (type == LUA_TTABLE) {
            sb.append(" count:${lua_rawlen(this, i).toInt()}")
            sb.append(" ptr:${lua_topointer(this, i).strPtr()}")
        }
        if (type == LUA_TSTRING) {
            sb.append(" value:\"${lua_tostring(this, i)}\"")
        }
        if (type == LUA_TLIGHTUSERDATA || type == LUA_TUSERDATA) {
            sb.append(" ptr:\"${lua_touserdata(this, i).strPtr()}\"")
        }
        StdOut.info(sb.toString())
    }
    StdOut.info("---===$msg===---")
}
