package pw.binom.lua

fun interface LuaFunction {
    fun call(req: InputVarargs, resp: OutputVarargs)
}