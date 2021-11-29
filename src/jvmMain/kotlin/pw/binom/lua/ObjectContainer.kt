package pw.binom.lua

import org.luaj.vm2.LuaError
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.LuaValue as LuaJValue

actual class ObjectContainer actual constructor() {

    actual fun makeClosure(func: LuaFunction): LuaValue.FunctionValue {
        val e = Adapter(func)
        return LuaValue.FunctionValue(e)
    }

    actual fun add(data: Any): LuaValue.UserData {
        return LuaValue.UserData(org.luaj.vm2.LuaUserdata(data))
    }

    actual fun get(data: LuaValue.UserData): Any? =
        data.native.m_instance

    actual fun remove(data: Any): Boolean {
        return false
    }

    actual fun getClosure(func: LuaValue.FunctionValue): LuaFunction?{
        val kotlinClosure = func.value as? Adapter
        return kotlinClosure?.func
    }

    private class Adapter(val func: LuaFunction) : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            try {
                val result = func.call(args.toCommon())
                return LuaJValue.varargsOf(result.map { it.makeNative() }.toTypedArray())
            } catch (e: Throwable) {
                throw LuaError(e)
            }
        }
    }

    actual fun clear() {
    }
}