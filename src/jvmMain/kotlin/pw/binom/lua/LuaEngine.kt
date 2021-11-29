package pw.binom.lua

import org.luaj.vm2.Varargs
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.lib.jse.JsePlatform

actual class LuaEngine {
    private val globals = JsePlatform.standardGlobals()

    actual fun eval(text: String): List<LuaValue> =
        try {
            globals.load(text).invoke().toCommon()
        } catch (e: LuaError) {
            throw LuaException(e.message, e)
        }

    actual operator fun get(name: String): LuaValue =
        LuaValue.of(globals.get(name), ref = true)

    actual operator fun set(name: String, value: LuaValue) {
        globals.set(name, value.makeNative())
    }

    actual fun call(
        functionName: String,
        vararg args: LuaValue
    ): List<LuaValue> {
        val func = globals.get(functionName)
        try {
            return func.invoke(args.toNative()).toCommon()
        } catch (e: LuaError) {
            throw LuaException(e.message)
        }
    }

    actual fun call(
        value: LuaValue,
        vararg args: LuaValue
    ): List<LuaValue> =
        try {
            value.makeNative().invoke(args.toNative()).toCommon()
        } catch (e: LuaError) {
            throw LuaException(e.message)
        }

    actual fun makeRef(value: LuaValue.FunctionValue): LuaValue.FunctionRef =
        LuaValue.FunctionRef(value.value)

    actual fun makeRef(value: LuaValue.TableValue): LuaValue.TableRef =
        LuaValue.TableRef(value.makeNative() as LuaTable)

    actual fun pin(ref: LuaValue.Ref): Boolean = false

    actual fun unpin(ref: LuaValue.Ref): Boolean = false

    actual val pinned: Set<LuaValue.Ref>
        get() = emptySet()

    actual fun freeAllPinned() {
    }
}

internal fun Varargs.toCommon() =
    (1..narg()).map {
        LuaValue.of(arg(it), ref = true)
    }

internal fun Array<out LuaValue>.toNative() = map { it.makeNative() }.toTypedArray()