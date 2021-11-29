package pw.binom.lua

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef

actual class ObjectContainer actual constructor() {
    private val ptrToObj = HashMap<COpaquePointer, Any>()
    private val objToPtr = HashMap<Any, COpaquePointer>()

    actual fun makeClosure(func: LuaFunction): LuaValue.FunctionValue =
        LuaValue.FunctionValue(ptr = userFunction, upvalues = listOf(add(func)))

    actual fun add(data: Any): LuaValue.LightUserData {
        val exist = objToPtr[data]
        if (exist != null) {
            return LuaValue.LightUserData(exist)
        }
        val dataStableRef = StableRef.create(data)
        ptrToObj[dataStableRef.asCPointer()] = data
        objToPtr[data] = dataStableRef.asCPointer()
        return LuaValue.LightUserData(dataStableRef.asCPointer())
    }

    actual fun remove(data: Any): Boolean {
        val ptr = objToPtr.remove(data) ?: return false
        ptrToObj.remove(ptr)
        ptr.asStableRef<Any>().dispose()
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
            it.asStableRef<LuaFunction>().dispose()
        }
        ptrToObj.clear()
        objToPtr.clear()
    }

    actual fun get(data: LuaValue.LightUserData): Any? =
        ptrToObj[data.lightPtr]
}