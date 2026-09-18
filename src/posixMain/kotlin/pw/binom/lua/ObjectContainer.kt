@file:OptIn(ExperimentalForeignApi::class)

package pw.binom.lua

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef

@OptIn(ExperimentalForeignApi::class)
actual class ObjectContainer actual constructor() {
    private val ptrToObj = HashMap<COpaquePointer, Any>()
    private val objToPtr = HashMap<Any, COpaquePointer>()

    actual fun makeClosure(func: LuaFunction): LuaValue.FunctionValue =
        LuaValue.FunctionValue(ptr = CLOSURE_FUNCTION, upValues = listOf(add(func)))

    actual fun add(data: Any?): LuaValue.LightUserData {
        if (data == null) {
            return LuaValue.LightUserData(null)
        }
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

    actual fun removeClosure(data: LuaValue.FunctionRef): Boolean {
        // The previous implementation compared `data.ptr` against the
        // cfunction sentinel `CLOSURE_FUNCTION` — but FunctionRef's `ptr`
        // is the address Lua reports for the underlying cclosure, not the
        // cfunction address. That always returned false (unless toValue()
        // happened to be cheap), leaving the bridge's StableRef stranded.
        // Resolve the closure's underlying LuaFunction via toValue() and
        // delegate to the (Any) overload, which knows how to dispose the
        // original entry point.
        return removeClosure(data.toValue())
    }

    actual fun removeClosure(data: LuaValue.FunctionValue): Boolean {
        if (data.upValues.size != 1) {
            return false
        }
        val upvalue = data.upValues[0]
        // The upvalue is a LightUserData wrapping a StableRef<LuaFunction>
        // for any closure built via makeClosure. Extract the underlying
        // Kotlin function so we can clean both maps by identity.
        val lud = upvalue as? LuaValue.LightUserData ?: return false
        val inner = lud.lightPtr ?: return false
        val underlying = inner.asStableRef<Any>().get()
        // Use remove(Any) (which knows the real identity path) and also
        // drop the LightUserData's StableRef to avoid leaking the upvalue.
        val removed = remove(underlying)
        lud.dispose()
        return removed
    }

    actual fun getClosure(func: LuaValue.FunctionValue): LuaFunction? {
        if (func.upValues.size != 1) {
            return null
        }
        val ptr = func.upValues[0].lightUserDataOrNull() ?: return null
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
