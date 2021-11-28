package pw.binom.lua

import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import org.luaj.vm2.lib.jse.JsePlatform

actual class LuaEngine {
    private val globals = JsePlatform.standardGlobals()

    actual fun eval(text: String) {
        val r = globals.load(text)
        r.call()
    }

    init {
        globals.set("test", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                return LuaValue.valueOf("ololo11 $args ${args.narg()}")
            }

            override fun call(): LuaValue {
                return LuaValue.valueOf("olololo")
            }
        })
    }

    actual fun dispose() {
    }

    actual fun setGlobal(name: String, value: pw.binom.lua.LuaValue) {
        globals.set(name, value.native)
    }

    actual fun getGlobal(name: String): pw.binom.lua.LuaValue =
        pw.binom.lua.LuaValue.of(globals.get(name))
}