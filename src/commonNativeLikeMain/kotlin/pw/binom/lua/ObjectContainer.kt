package pw.binom.lua

actual class ObjectContainer actual constructor() {
    private val ptrToObj = HashMap<COpaquePointer1, Any>()
    private val objToPtr = HashMap<Any, COpaquePointer1>()

    actual fun makeClosure(func: LuaFunction): LuaValue.FunctionValue =
        LuaValue.FunctionValue(ptr = CLOSURE_FUNCTION, upvalues = listOf(add(func)))

    actual fun add(data: Any?): LuaValue.LightUserData {
        if (data == null) {
            return LuaValue.LightUserData(null)
        }
        val exist = objToPtr[data]
        if (exist != null) {
            return LuaValue.LightUserData(exist)
        }
        val dataStableRef = StableRef1.create(data)
        ptrToObj[dataStableRef.asCPointer()] = data
        objToPtr[data] = dataStableRef.asCPointer()
        return LuaValue.LightUserData(dataStableRef.asCPointer())
    }

    actual fun remove(data: Any): Boolean {
        val ptr = objToPtr.remove(data) ?: return false
        ptrToObj.remove(ptr)
        ptr.asStableRef1<Any>().dispose()
        return true
    }

    actual fun removeClosure(data: LuaValue.FunctionRef): Boolean {
        if (data.ptr != CLOSURE_FUNCTION)
            return false
        return remove(data.toValue())
    }

    actual fun removeClosure(data: LuaValue.FunctionValue): Boolean {
        if (data.upvalues.size != 1) {
            return false
        }
        val func = data.upvalues[0]
        if (func is LuaValue.LightUserData && func.value is LuaFunction) {
            func.dispose()
            return true
        }
        return true
    }

    actual fun getClosure(func: LuaValue.FunctionValue): LuaFunction? {
        if (func.upvalues.size != 1) {
            return null
        }
        val ptr = func.upvalues[0].lightUserDataOrNull() ?: return null
        return get(ptr) as? LuaFunction
    }

    actual fun clear() {
        ptrToObj.keys.forEach {
            it.asStableRef1<LuaFunction>().dispose()
        }
        ptrToObj.clear()
        objToPtr.clear()
    }

    actual fun get(data: LuaValue.LightUserData): Any? =
        ptrToObj[data.lightPtr]
}