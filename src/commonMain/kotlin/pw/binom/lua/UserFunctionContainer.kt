package pw.binom.lua

expect class UserFunctionContainer {
    constructor()

    fun add(func: LuaFunction): LuaValue.Function
    fun remove(func: LuaFunction): Boolean
    fun getUserFunction(func: LuaValue.Function): LuaFunction?
}