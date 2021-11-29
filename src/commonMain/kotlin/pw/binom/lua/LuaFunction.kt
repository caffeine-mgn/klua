package pw.binom.lua

fun interface LuaFunction {
    fun call(req: List<LuaValue>):List<LuaValue>
}