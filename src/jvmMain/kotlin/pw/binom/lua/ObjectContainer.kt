package pw.binom.lua

actual class ObjectContainer actual constructor() {

    actual fun makeClosure(func: LuaFunction): LuaValue.FunctionValue {
        val e = ClosureAdapter(func)
        return LuaValue.FunctionValue(e)
    }

    actual fun add(data: Any): LuaValue.LightUserData {
        return LuaValue.LightUserData(data)
    }

    actual fun get(data: LuaValue.LightUserData): Any? =
        data.value

    actual fun remove(data: Any): Boolean {
        return false
    }

    actual fun getClosure(func: LuaValue.FunctionValue): LuaFunction?{
        val kotlinClosure = func.value as? ClosureAdapter
        return kotlinClosure?.func
    }

    actual fun clear() {
    }
}