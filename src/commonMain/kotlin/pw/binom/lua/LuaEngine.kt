package pw.binom.lua

expect class LuaEngine {
    constructor()

    fun eval(text: String): List<LuaValue>
    fun dispose()
    operator fun get(name: String): LuaValue
    operator fun set(name: String, value: LuaValue)
    fun call(functionName: String, vararg args: LuaValue): List<LuaValue>
}
