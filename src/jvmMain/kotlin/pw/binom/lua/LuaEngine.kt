package pw.binom.lua

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaUserdata
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.jse.JsePlatform

actual class LuaEngine : AutoCloseable {
    private val globals = JsePlatform.standardGlobals()

    actual fun eval(text: String): List<LuaValue> =
        try {
            globals.load(text).invoke().toCommon()
        } catch (e: LuaError) {
            throw LuaException(e.message, e)
        }

    actual override fun close() {
    }

    actual val closureAutoGcFunction: LuaValue.FunctionRef =
        makeRef(LuaValue.FunctionValue(ClosureAdapter { emptyList() }))

    actual val userdataAutoGcFunction: LuaValue.FunctionRef =
        makeRef(LuaValue.FunctionValue(ClosureAdapter { emptyList() }))

    actual operator fun get(name: String): LuaValue =
        LuaValue.of(globals.get(name), ref = true)

    actual operator fun set(name: String, value: LuaValue) {
        globals.set(name, value.makeNative())
    }

    actual fun call(
        functionName: String,
        vararg args: LuaValue,
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
        vararg args: LuaValue,
    ): List<LuaValue> =
        try {
            value.makeNative().invoke(args.toNative()).toCommon()
        } catch (e: LuaError) {
            throw LuaException(e.message)
        }

    actual fun makeRef(value: LuaValue.FunctionValue): LuaValue.FunctionRef =
        LuaValue.FunctionRef(value.value)

    actual fun makeRef(value: LuaValue.TableValue): LuaValue.TableRef {
        val t = LuaValue.TableRef(value.makeNative() as KLuaTable)
        t.metatable = value.metatable
        return t
    }

    actual fun createUserData(value: LuaValue.LightUserData): LuaValue.UserData =
        LuaValue.UserData(KLuaUserdata(value.value))

    actual fun createUserData(value: Any): LuaValue.UserData =
        LuaValue.UserData(KLuaUserdata(value))

    actual fun createACClosure(func: LuaFunction): LuaValue.UserData {
        val metatable = LuaTable()
        metatable.rawset("__call", ClosureAdapter(func))
        return LuaValue.UserData(KLuaUserdata(AC_CLOSURE_PTR, metatable))
    }

    actual fun setAC(userdata: LuaValue.UserData) {
        val table = userdata.metatable
        if (table is LuaValue.TableValue) {
            table["__gc".lua] = LuaValue.Nil
        } else {
            userdata.metatable = LuaValue.TableValue()
        }
    }

    actual fun createAC(value: LuaValue.LightUserData): LuaValue.UserData =
        LuaValue.UserData(KLuaUserdata(value, LuaTable()))

    actual fun createAC(value: Any?): LuaValue.UserData =
        LuaValue.UserData(KLuaUserdata(value, LuaTable()))
}

internal val AC_CLOSURE_PTR = Any()

internal fun Varargs.toCommon(): List<LuaValue> {
    return (1..narg()).mapNotNull {
        val item = arg(it)
        if (item is LuaUserdata && item.m_instance === AC_CLOSURE_PTR) {
            return@mapNotNull null
        }
        LuaValue.of(item, ref = true)
    }
}

internal fun Array<out LuaValue>.toNative() = map { it.makeNative() }.toTypedArray()
