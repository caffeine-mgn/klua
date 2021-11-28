package pw.binom.lua

interface InputVarargs {
    val size: Int
    operator fun get(index: Int): LuaValue
}