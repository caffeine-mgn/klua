package pw.binom.lua

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import platform.internal_lua.*

@OptIn(ExperimentalForeignApi::class)
actual class LuaEngine actual constructor() : AutoCloseable {

    internal val ll = LuaContext()

    actual val closureAutoGcFunction: LuaValue.FunctionRef =
        makeRef(LuaValue.FunctionValue(userdataGc))
    actual val userdataAutoGcFunction: LuaValue.FunctionRef =
        makeRef(LuaValue.FunctionValue(userdataGc))


    actual override fun close() {
        // Mirror the JVM contract: explicitly release the Lua state and
        // unregister from the global registry so subsequent calls into the
        // Cleaner's SafeRefList don't keep this engine's LuaContext alive
        // forever. The Cleaner that backs the LuaContext wrapper remains
        // as a safety net for callers who forget to close().
        val s = ll.state
        if (s != null) {
            LuaContextRegistry.unregister(s)
            lua_close(s)
        }
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
            // Pop the nil pushed by lua_getglobal before throwing; the JVM
            // counterpart pops in both branches and the POSIX side already
            // pops in the not-a-function branch. Without this pop the call
            // accumulates one stack slot per failed lookup.
            lua_pop(ll.state, 1)
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

    /**
     * POSIX implementation of [openStandardLibs]. Delegates to
     * [LuaContext.openStandardLibs] which invokes [luaL_openlibs] to load
     * every standard library into the engine's state.
     */
    actual fun openStandardLibs() {
        ll.openStandardLibs()
    }

    actual fun makeRef(value: LuaValue.FunctionValue): LuaValue.FunctionRef {
        ll.state.checkState {
            ll.pushValue(value)
            // lua_topointer returns null for values without a GC object
            // (booleans, nil, numbers, lightuserdata); throw a typed
            // exception instead of NPE so callers can react to the
            // "not a Lua object" condition explicitly.
            val ptr = lua_topointer(ll.state, -1)
                ?: throw LuaException("Cannot makeRef of value with no GC pointer")
            val ref = ll.state.makeRef(popValue = true)
            return LuaValue.FunctionRef(ref = ref, ptr = ptr, ll = ll)
        }
    }

    actual fun makeRef(value: LuaValue.TableValue): LuaValue.TableRef {
        ll.state.checkState {
            ll.pushValue(value)
            // See [makeRef(FunctionValue)] — throw a typed exception
            // instead of NPE on values without a GC pointer.
            val ptr = lua_topointer(ll.state, -1)
                ?: throw LuaException("Cannot makeRef of value with no GC pointer")
            val ref = ll.state.makeRef(popValue = true)
            return LuaValue.TableRef(ref = ref, ptr = ptr, ll = ll)
        }
    }

    actual fun createUserData(value: LuaValue.LightUserData): LuaValue.UserData {
        ll.state.checkState {
            val mem = lua_newuserdata1(ll.state, Heap.PTR_SIZE)!!
            Heap.setPtrFromPtr(mem, value = value.lightPtr)
            val ret = LuaValue.UserData(ll.state.makeRef(), ll)
            // Install __gc mirroring JVM createUserData(LightUserData) so
            // the underlying StableRef is disposed when Lua collects the
            // userdata. Previously this branch silently skipped the
            // metatable, leaking every AC userdata's payload.
            ret.metatable = LuaValue.TableValue("__gc".lua to userdataAutoGcFunction)
            return ret
        }
    }

    actual fun createUserData(value: Any): LuaValue.UserData {
        val ptr = StableRef.create(value)
        try {
            val ret = createUserData(LuaValue.LightUserData(ptr.asCPointer()))
            ret.metatable = LuaValue.of(
                mapOf(LuaValue.of("__gc") to closureAutoGcFunction)
            )
            return ret
        } catch (e: Throwable) {
            ptr.dispose()
            throw e
        }
    }

    actual fun createACClosure(func: LuaFunction): LuaValue.UserData {
        // Single source of truth: the userdata's payload holds the only
        // StableRef<LuaFunction>. The metatable's __call installs a Lua
        // closure backed by AC_CLOSURE_FUNCTION (a no-upvalue cfunction)
        // that reads the same ref from the userdata payload at idx 1. When
        // Lua's __gc fires, userdataGc disposes the payload's StableRef and
        // the Lua closure has nothing to dangle — closing the UAF window
        // reported after the d90a8f8 JVM-side fix.
        val ref = StableRef.create(func)
        val luaFunc = LuaValue.FunctionValue(
            ptr = AC_CLOSURE_FUNCTION,
            upValues = emptyList(),
        )
        val userData = createUserData(LuaValue.LightUserData(ref.asCPointer()))
        // createUserData already installed a default __gc metatable mapping
        // to userdataAutoGcFunction (which on POSIX aliases userdataGc);
        // augment it with __call but DO NOT overwrite __gc.
        val existing = userData.metatable
        val merged = when (existing) {
            is LuaValue.TableValue -> existing.map.toMutableMap()
            is LuaValue.Table -> existing.toMap().toMutableMap()
            else -> mutableMapOf<LuaValue, LuaValue>()
        }
        merged[LuaValue.of("__call")] = luaFunc
        userData.metatable = LuaValue.TableValue(merged)
        return userData
    }

    actual fun setAC(userdata: LuaValue.UserData) {
        val table = userdata.metatable
        if (table is LuaValue.Table) {
            table["__gc".lua] = closureAutoGcFunction
        } else {
            userdata.metatable = LuaValue.TableValue("__gc".lua to closureAutoGcFunction)
        }
    }

    actual fun createAC(value: LuaValue.LightUserData): LuaValue.UserData {
        val ud = createUserData(value)
        setAC(ud)
        return ud
    }

    actual fun createAC(value: Any?): LuaValue.UserData {
        // Capture the StableRef so the catch path can dispose it on
        // failure — without this, a throw from createAC(LightUserData(...))
        // (e.g. on Lua OOM) leaks the ref and pins the Kotlin object for
        // the lifetime of the engine. Also drop the printStackTrace spam;
        // the exception propagates unchanged.
        if (value == null) {
            return createAC(LuaValue.LightUserData(null))
        }
        val ref = StableRef.create(value)
        return try {
            createAC(LuaValue.LightUserData(ref.asCPointer()))
        } catch (t: Throwable) {
            ref.dispose()
            throw t
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun LuaContext.callClosure(vararg args: LuaValue): List<LuaValue> {
    args.forEach {
        pushValue(it)
    }
    val exec = lua_pcall1(state, args.size, LUA_MULTRET, 0)
    return pcallProcessing(this, exec)
}

@OptIn(ExperimentalForeignApi::class)
private fun pcallProcessing(luaLib: LuaContext, exeCode: Int): List<LuaValue> {
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
            // Multi-value Lua errors can leave several entries on the stack
            // before luaL_traceback is called. Walk all of them into the
            // traceback so the original N-1 error values do not leak across
            // subsequent operations. The single-string fast path pops the
            // lone entry after reading it.
            val topBefore = lua_gettop(luaLib.state)
            val message =
                if (topBefore == 1 && lua_isstring(luaLib.state, 1) != 0) {
                    val str = lua_tostring(luaLib.state, 1)
                    if (topBefore > 0) lua_pop(luaLib.state, topBefore)
                    str
                } else {
                    if (topBefore > 0) lua_pop(luaLib.state, topBefore)
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
