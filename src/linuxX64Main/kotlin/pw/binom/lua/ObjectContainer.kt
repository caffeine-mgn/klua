package pw.binom.lua

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef

actual class ObjectContainer actual constructor() {
    private val ptrToObj = HashMap<COpaquePointer, Any>()
    private val objToPtr = HashMap<Any, COpaquePointer>()

    actual fun makeClosure(func: LuaFunction): LuaValue.FunctionValue =
        LuaValue.FunctionValue(ptr = userFunction, upvalues = listOf(add(func)))

    actual fun add(data: Any): LuaValue.UserData {
        val exist = objToPtr[data]
        if (exist != null) {
            return LuaValue.UserData(exist)
        }
        val function = StableRef.create(data)
        ptrToObj[function.asCPointer()] = data
        objToPtr[data] = function.asCPointer()
        return LuaValue.UserData(function.asCPointer())
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
        val ptr = func.upvalues[0].userDataOrNull() ?: return null
        return get(ptr) as? LuaFunction
    }

    actual fun clear() {
        ptrToObj.keys.forEach {
            it.asStableRef<LuaFunction>().dispose()
        }
        ptrToObj.clear()
        objToPtr.clear()
    }

    actual fun get(data: LuaValue.UserData): Any? =
        ptrToObj[data.lightPtr]
}