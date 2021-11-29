package pw.binom.lua

import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import platform.internal_lua.*

@Deprecated(message = "Debug Tool", level = DeprecationLevel.WARNING)
actual fun LuaEngine.printStack(message: String?) =
    state.printStack(message)

/**
 * Draws stack without any changes
 */
@Deprecated(message = "Debug Tool", level = DeprecationLevel.WARNING)
fun LuaState.printStack(message: String? = null) {
    val msg = message ?: "Lua Stack"
    println("---===$msg===---")
    val count = lua_gettop(this)
    for (i in 1..count) {
        val type = lua_type(this, i)
        val typename = lua_typename(this, type)?.toKString()
        val sb = StringBuilder("$i. type:$typename")
        if (type == LUA_TTABLE) {
            sb.append(" count:${lua_rawlen(this, i)}")
            sb.append(" ptr:${lua_topointer(this, i)!!.toLong().toString(16)}")
        }
        if (type == LUA_TSTRING) {
            sb.append(" value:\"${lua_tostring(this, i)}\"")
        }
        if (type == LUA_TLIGHTUSERDATA || type == LUA_TUSERDATA) {
            sb.append(" ptr:\"${lua_touserdata(this, i)?.toLong()?.toString(16) ?: "0"}\"")
        }
        println(sb.toString())
    }
    println("---===$msg===---")
}