package pw.binom.lua

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef

actual class UserFunctionContainer actual constructor() {
    private val bindedPtr2Func = HashMap<COpaquePointer, LuaFunction>()
    private val bindedFunc2Ptr = HashMap<LuaFunction, COpaquePointer>()

    actual fun add(func: LuaFunction): LuaValue.Function {
        val exist = bindedFunc2Ptr[func]
        if (exist != null) {
            return LuaValue.Function(ptr = null, implPtr = exist)
        }
        val function = StableRef.create(func)
        bindedPtr2Func[function.asCPointer()] = func
        bindedFunc2Ptr[func] = function.asCPointer()
        return LuaValue.Function(ptr = null, implPtr = function.asCPointer())
    }

    actual fun remove(func: LuaFunction): Boolean {
        val ptr = bindedFunc2Ptr[func] ?: return false
        ptr.asStableRef<LuaFunction>().dispose()
        return true
    }

    actual fun getUserFunction(func: LuaValue.Function): LuaFunction? =
        bindedPtr2Func[func.implPtr]
}