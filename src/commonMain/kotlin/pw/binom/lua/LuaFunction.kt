package pw.binom.lua

fun interface LuaFunction {
    fun call(args: List<LuaValue>): List<LuaValue>
}
