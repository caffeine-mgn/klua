package pw.binom.lua

import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.LuaValue as LuaJValue

actual class UserFunctionContainer actual constructor() {

    private val bindedPtr2Func = HashMap<Adapter, LuaFunction>()
    private val bindedFunc2Ptr = HashMap<LuaFunction, Adapter>()

    actual fun add(func: LuaFunction): LuaValue.Function {
        val exist = bindedFunc2Ptr[func]
        if (exist != null) {
            return LuaValue.Function(exist)
        }
        val e = Adapter(func)
        bindedPtr2Func[e] = func
        bindedFunc2Ptr[func] = e
        return LuaValue.Function(e)
    }

    actual fun remove(func: LuaFunction): Boolean {
        val e = bindedFunc2Ptr.remove(func) ?: return false
        bindedPtr2Func.remove(e)
        return true
    }

    actual fun getUserFunction(func: LuaValue.Function): LuaFunction? =
        bindedPtr2Func[func.value]

    private class Adapter(val func: LuaFunction) : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            val input = InputVarargsAdapter(args)
            val output = OutputVarargsAdapter()
            func.call(input, output)
            return LuaJValue.varargsOf(output.list.map { it.native }.toTypedArray())
        }
    }

    private class InputVarargsAdapter(val native: Varargs) : InputVarargs {
        override val size: Int
            get() = native.narg()

        override fun get(index: Int): LuaValue = LuaValue.of(native.arg(index + 1))
    }

    private class OutputVarargsAdapter : OutputVarargs {
        val list = ArrayList<LuaValue>()
        override fun plusAssign(value: LuaValue) {
            list += value
        }

    }
}