package pw.binom.lua

expect class LuaEngine {
    constructor()

    fun eval(text: String)
    fun dispose()

    //    fun reg(name: String, func: LuaFunction)
    fun setGlobal(name: String, value: LuaValue)
    fun getGlobal(name: String): LuaValue
}

expect class UserFunctionContainer {
    constructor()

    fun add(func: LuaFunction): LuaValue.Function
    fun remove(func: LuaFunction): Boolean
    fun getUserFunction(func: LuaValue.Function): LuaFunction?
}


enum class LuaValueType {
    NIL,
    NUMBER,
    BOOLEAN,
    STRING,
    TABLE,
    FUNCTION,
    USERDATA,
    THREAD,
    LIGHTUSERDATA,
}

interface InputVarargs {
    val size: Int
    operator fun get(index: Int): LuaValue
}

interface OutputVarargs {
    operator fun plusAssign(value: LuaValue)
}

fun interface LuaFunction {
    fun call(req: InputVarargs, resp: OutputVarargs)
}