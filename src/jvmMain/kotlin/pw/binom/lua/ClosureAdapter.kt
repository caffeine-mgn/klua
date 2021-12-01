package pw.binom.lua

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction

class ClosureAdapter(val func: LuaFunction) : VarArgFunction() {
    override fun invoke(args: Varargs): Varargs {
        try {
            val result = func.call(args.toCommon())
            return LuaValue.varargsOf(result.map { it.makeNative() }.toTypedArray())
        } catch (e: Throwable) {
            throw LuaError(e)
        }
    }
}