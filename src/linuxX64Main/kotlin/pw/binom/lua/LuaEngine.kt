@file:OptIn(ExperimentalStdlibApi::class)

package pw.binom.lua

import platform.internal_lua.*
import pw.binom.concurrency.SpinLock
import pw.binom.concurrency.synchronize
import kotlin.native.internal.createCleaner

actual class LuaEngine {

    val state: LuaState = luaL_newstate()!!

    init {
        luaL_openlibs(state)
    }

    private var internalPinned = HashMap<LuaValue.Ref, Int>()
    actual val pinned: Set<LuaValue.Ref>
        get() = internalPinned.keys

    private val cleaner = createCleaner(state) {
        lua_close(it)
    }

    actual operator fun get(name: String): LuaValue {
        lua_getglobal(state, name)
        return state.readValue(-1, true)
    }

    actual operator fun set(name: String, value: LuaValue) {
        state.pushValue(value)
        lua_setglobal(state, name)
    }

    actual fun eval(text: String): List<pw.binom.lua.LuaValue> {
        val r = luaL_loadstring(state, text)
        when (r) {
            0 -> {}
            LUA_ERRSYNTAX -> {
                val msg = lua_tostring(state, -1)
                lua_pop(state, 1)
                throw LuaException(msg ?: "Compile error")
            }
            LUA_ERRMEM -> throw LuaException("LUA_ERRMEM")
            else -> throw LuaException("Can't eval text \"$text\"")
        }
        val exitCode = lua_pcall(state, 0, LUA_MULTRET, 0)
        return pcallProcessing(state, exitCode)
    }

    actual fun call(
        functionName: String,
        vararg args: LuaValue
    ): List<LuaValue> {
        lua_getglobal(state, functionName)
        if (lua_isnil(state, -1)) {
            throw LuaException("Function \"$functionName\" not found")
        }
        if (!lua_isfunction(state, -1)) {
            lua_pop(state, 1)
            throw LuaException("\"$functionName\" is not a function")
        }
        args.forEach {
            state.pushValue(it)
        }
        val exec = lua_pcall(state, args.size, LUA_MULTRET, 0)
        return pcallProcessing(state, exec)
    }

    actual fun call(
        value: LuaValue,
        vararg args: LuaValue
    ): List<LuaValue> {
        println("try call $value...")
        println("#1 lock got!")
        state.pushValue(value)
        println("#2")
        args.forEach {
            println("#3")
            state.pushValue(it)
            println("#4")
        }
        println("#5")
        val exec = lua_pcall(state, args.size, LUA_MULTRET, 0)
        println("#6")
        return pcallProcessing(state, exec)
    }

    actual fun makeRef(value: LuaValue.FunctionValue): LuaValue.FunctionRef {
        state.pushValue(value)
//        val ref = luaL_ref(state, LUA_REGISTRYINDEX)
        val ref = klua_get_value(state, -1)!!
        state.pop(0)
        return LuaValue.FunctionRef(ref, state)
    }

    actual fun makeRef(value: LuaValue.TableValue): LuaValue.TableRef {
        state.pushValue(value)
        val ref = klua_get_value(state, -1)!!
        state.pop(0)
        return LuaValue.TableRef(ref = ref, state)
    }

    actual fun pin(ref: LuaValue.Ref): Boolean {
        if (ref in internalPinned) {
            return false
        }
        internalPinned[ref] = luaL_ref(state, LUA_REGISTRYINDEX)
        return true
    }

    actual fun unpin(ref: LuaValue.Ref): Boolean {
        val refId = internalPinned.remove(ref) ?: return false
        luaL_unref(state, LUA_REGISTRYINDEX, refId)
        return true
    }

    actual fun freeAllPinned() {
        internalPinned.forEach {
            luaL_unref(state, LUA_REGISTRYINDEX, it.value)
        }
        internalPinned.clear()
    }
}

fun LuaState.call(vararg args: LuaValue): List<LuaValue> {
    args.forEach {
        pushValue(it)
    }
    val exec = lua_pcall(this, args.size, LUA_MULTRET, 0)
    return pcallProcessing(this, exec)
}

private fun pcallProcessing(state: LuaState, exeCode: Int): List<LuaValue> {
    when (exeCode) {
        LUA_OK -> {
            val count = lua_gettop(state)
            val list = (1..count).map {
                state.readValue(it, true)
            }
            lua_pop(state, count)
            return list
        }
        LUA_ERRRUN -> {
            val message = if (lua_gettop(state) == 1 && lua_isstring(state, 1) != 0) {
                val str = lua_tostring(state, 1)
                lua_pop(state, 1)
                str
            } else {
                null
            }
            luaL_traceback(state, state, message, 1)
            val fullMessage = lua_tostring(state, -1)
            lua_pop(state, 1)
            throw LuaException(fullMessage)
        }
        LUA_ERRMEM -> throw RuntimeException("memory allocation error. For such errors, Lua does not call the message handler.")
        LUA_ERRERR -> throw RuntimeException("error while running the message handler.")
        else -> throw RuntimeException("Unknown invoke status")
    }
}