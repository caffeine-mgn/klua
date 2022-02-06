package pw.binom.lua

actual class ObjectContainer actual constructor() {

    actual fun makeClosure(func: LuaFunction): LuaValue.FunctionValue = LuaValue.FunctionValue(ClosureAdapter(func))
    actual fun add(data: Any?): LuaValue.LightUserData = LuaValue.LightUserData(data)
    actual fun get(data: LuaValue.LightUserData): Any? = data.value
    actual fun remove(data: Any): Boolean = false
    actual fun getClosure(func: LuaValue.FunctionValue): LuaFunction? {
        val kotlinClosure = func.value as? ClosureAdapter
        return kotlinClosure?.func
    }

    actual fun clear() = Unit
    actual fun removeClosure(data: LuaValue.FunctionRef): Boolean = false
    actual fun removeClosure(data: LuaValue.FunctionValue): Boolean = false
}
