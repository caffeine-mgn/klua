package pw.binom.lua

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import platform.internal_lua.*
import kotlin.concurrent.AtomicInt
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner

@OptIn(ExperimentalForeignApi::class)
actual class LuaEngine actual constructor() : AutoCloseable {
    internal val ll = LuaStateAndLib(
        luaL_newstate() ?: throw RuntimeException("Can't create Lua State"),
    )
    private val contextPtr = StableRef.create(ll)

    @OptIn(ExperimentalNativeApi::class)
    private val cleaner = createCleaner(contextPtr) {
        it.dispose()
    }

    actual val closureAutoGcFunction: LuaValue.FunctionRef =
        makeRef(LuaValue.FunctionValue(closureGc, upValues = listOf(LuaValue.LightUserData(contextPtr))))
    actual val userdataAutoGcFunction: LuaValue.FunctionRef =
        makeRef(LuaValue.FunctionValue(userdataGc, upValues = listOf(LuaValue.LightUserData(contextPtr))))


    init {
        luaL_openlibs(ll.state)
    }

    actual override fun close() {
    }

    actual operator fun get(name: String): LuaValue {
        ll.state.checkState {
            lua_getglobal(ll.state, name)
            val value = ll.readValue(-1, true)
            lua_pop(ll.state, 1)
            return value
        }
    }

    actual operator fun set(name: String, value: LuaValue) {
        ll.state.checkState {
            ll.pushValue(value)
            lua_setglobal(ll.state, name)
        }
    }

    actual fun eval(text: String): List<pw.binom.lua.LuaValue> {
        val r = luaL_loadstring(ll.state, text)
        when (r) {
            0 -> {}
            LUA_ERRSYNTAX -> {
                val msg = lua_tostring(ll.state, -1)
                lua_pop(ll.state, 1)
                throw LuaException(msg ?: "Compile error")
            }

            LUA_ERRMEM -> throw LuaException("LUA_ERRMEM")
            else -> throw LuaException("Can't eval text \"$text\"")
        }
        val exitCode = lua_pcall1(ll.state, 0, LUA_MULTRET, 0)
        return pcallProcessing(ll, exitCode)
    }

    actual fun call(
        functionName: String,
        vararg args: LuaValue,
    ): List<LuaValue> {
        lua_getglobal(ll.state, functionName)
        if (lua_isnil1(ll.state, -1)) {
            throw LuaException("Function \"$functionName\" not found")
        }
        if (!lua_isfunction1(ll.state, -1)) {
            lua_pop(ll.state, 1)
            throw LuaException("\"$functionName\" is not a function")
        }
        args.forEach {
            ll.pushValue(it)
        }
        val exec = lua_pcall1(ll.state, args.size, LUA_MULTRET, 0)
        return pcallProcessing(ll, exec)
    }

    actual fun call(
        value: LuaValue,
        vararg args: LuaValue,
    ): List<LuaValue> {
        ll.pushValue(value)
        args.forEach {
            ll.pushValue(it)
        }
        val exec = lua_pcall1(ll.state, args.size, LUA_MULTRET, 0)
        return pcallProcessing(ll, exec)
    }

    actual fun makeRef(value: LuaValue.FunctionValue): LuaValue.FunctionRef {
        ll.state.checkState {
            try {
                ll.pushValue(value)
                val ptr = lua_topointer(ll.state, -1)!!
                val ref = ll.state.makeRef(popValue = true)
                return LuaValue.FunctionRef(ref = ref, ptr = ptr, ll = ll)
            } catch (e: Throwable) {
                e.printStackTrace()
                throw e
            }
        }
    }

    actual fun makeRef(value: LuaValue.TableValue): LuaValue.TableRef {
        ll.state.checkState {
            ll.pushValue(value)
            val ptr = lua_topointer(ll.state, -1)!!
            val ref = ll.state.makeRef(popValue = true)
            return LuaValue.TableRef(ref = ref, ptr = ptr, ll = ll)
        }
    }

    actual fun createUserData(value: LuaValue.LightUserData): LuaValue.UserData {
        ll.state.checkState {
            val mem = lua_newuserdata1(ll.state, Heap.PTR_SIZE)!!
            Heap.setPtrFromPtr(mem, value = value.lightPtr)
            val ret = LuaValue.UserData(ll.state.makeRef(), ll)
            return ret
        }
    }

    actual fun createUserData(value: Any): LuaValue.UserData {
        val ptr = StableRef.create(value)
        try {
            val mem = lua_newuserdata1(ll.state, Heap.PTR_SIZE)!!
            Heap.setPtrFromPtr(mem, value = ptr.asCPointer())
            val ret = LuaValue.UserData(ll.state.makeRef(), ll)
            ret.metatable = LuaValue.of(
                mapOf(LuaValue.of("__gc") to userdataAutoGcFunction)
            )
            return ret
        } catch (e: Throwable) {
            ptr.dispose()
            throw e
        }
    }

    actual fun createACClosure(func: LuaFunction): LuaValue.UserData {
        val ref = StableRef.create(func)
        val luaFunc = LuaValue.FunctionValue(
            ptr = CLOSURE_FUNCTION,
            upValues = listOf(
                LuaValue.LightUserData(ref.asCPointer()),
                LuaValue.LightUserData(contextPtr),
            ),
        )
        val metatable = LuaValue.TableValue(
            "__call".lua to luaFunc,
            "__gc".lua to closureAutoGcFunction
        )
        val userData = createUserData(LuaValue.LightUserData(AC_CLOSURE_PTR))
        userData.metatable = metatable
        return userData
    }

    actual fun setAC(userdata: LuaValue.UserData) {
        val table = userdata.metatable
        if (table is LuaValue.Table) {
            table["__gc".lua] = userdataAutoGcFunction
        } else {
            userdata.metatable = LuaValue.TableValue("__gc".lua to userdataAutoGcFunction)
        }
    }

    actual fun createAC(value: LuaValue.LightUserData): LuaValue.UserData {
        val ud = createUserData(value)
        setAC(ud)
        return ud
    }

    actual fun createAC(value: Any?): LuaValue.UserData {
        try {
            val ref = value?.let { StableRef.create(it) }?.asCPointer()
            return createAC(LuaValue.LightUserData(ref))
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun LuaStateAndLib.callClosure(vararg args: LuaValue): List<LuaValue> {
    args.forEach {
        pushValue(it)
    }
    val exec = lua_pcall1(state, args.size, LUA_MULTRET, 0)
    return pcallProcessing(this, exec)
}

@OptIn(ExperimentalForeignApi::class)
private fun pcallProcessing(luaLib: LuaStateAndLib, exeCode: Int): List<LuaValue> {
    when (exeCode) {
        LUA_OK -> {
            val count = lua_gettop(luaLib.state)
            val list = (1..count).map {
                luaLib.readValue(it, true)
            }
            lua_pop(luaLib.state, count)
            return list
        }

        LUA_ERRRUN -> {
            val message =
                if (lua_gettop(luaLib.state) == 1 && lua_isstring(luaLib.state, 1) != 0) {
                    val str = lua_tostring(luaLib.state, 1)
                    lua_pop(luaLib.state, 1)
                    str
                } else {
                    null
                }
            luaL_traceback(luaLib.state, luaLib.state, message, 1)
            val fullMessage = lua_tostring(luaLib.state, -1)
            lua_pop(luaLib.state, 1)
            throw LuaException(fullMessage)
        }

        LUA_ERRMEM -> throw RuntimeException("memory allocation error. For such errors, Lua does not call the message handler.")
        LUA_ERRERR -> throw RuntimeException("error while running the message handler.")
        else -> throw RuntimeException("Unknown invoke status")
    }
}
