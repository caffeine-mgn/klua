package pw.binom.lua

import org.luaj.vm2.*
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import org.luaj.vm2.lib.jse.JsePlatform

actual class LuaEngine {
    private val globals = JsePlatform.standardGlobals()

    actual fun eval(text: String): List<pw.binom.lua.LuaValue> {
        try {
            val r = globals.load(text)
            val result = r.invoke()
            return (0 until result.narg()).map {
                pw.binom.lua.LuaValue.of(result.arg(it + 1))
            }
        } catch (e: LuaError) {
            throw LuaException(e.message)
        }
    }

    actual fun dispose() {
    }

    actual operator fun get(name: String): pw.binom.lua.LuaValue =
        pw.binom.lua.LuaValue.of(globals.get(name))

    actual operator fun set(name: String, value: pw.binom.lua.LuaValue) {
        globals.set(name, value.native)
    }

    actual fun call(
        functionName: String,
        vararg args: pw.binom.lua.LuaValue
    ): List<pw.binom.lua.LuaValue> {
        val func = globals.get(functionName)
        if (func.isnil()) {
            throw LuaException("Function \"$functionName\" not found")
        }
        if (!func.isfunction()) {
            throw LuaException("\"$functionName\" is not a function")
        }
        val c = func.invoke(args.map { it.native }.toTypedArray())
        return (0 until c.narg()).map {
            pw.binom.lua.LuaValue.of(c.arg(0))
        }
    }
}