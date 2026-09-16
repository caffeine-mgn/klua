@file:OptIn(ExperimentalForeignApi::class)

package pw.binom.lua

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import platform.internal_lua.LUA_TLIGHTUSERDATA
import platform.internal_lua.luaL_error
import platform.internal_lua.lua_CFunction
import platform.internal_lua.lua_gettop
import platform.internal_lua.lua_type
import kotlin.experimental.ExperimentalNativeApi

/**
 * Sentinel C function pointer used as the payload of [LuaEngine.createACClosure]'s
 * wrapper userdata. The CLOSURE_FUNCTION dispatch uses the marker to recognise
 * and discard the auto-prepended userdata argument (Lua semantics: when an
 * object with a `__call` metamethod is invoked as `obj(a,b,c)`, Lua shifts
 * `obj` into the args list before invoking the metamethod).
 */
val AC_CLOSURE_PTR: lua_CFunction = staticCFunction { state -> 0 }

/**
 * C entry-point for any Lua closure created by [ObjectContainer.makeClosure]
 * or [LuaEngine.createACClosure].
 *
 * Upvalue layout:
 *   upvalue(1) = LUA_TLIGHTUSERDATA holding a StableRef<LuaFunction> pointer.
 *
 * The [LuaContext] is recovered via [LuaContextRegistry] — Lua/Native gives us
 * no way to attach arbitrary userdata to a lua_State, so we maintain a global
 * (state → context) pointer set when the engine is constructed.
 */
val CLOSURE_FUNCTION: lua_CFunction = staticCFunction { state ->
    try {
        if (state == null) {
            return@staticCFunction 0
        }
        val ctx = LuaContextRegistry.lookup(state)
            ?: run {
                luaL_error(state, "LuaContext not registered for this state")
                return@staticCFunction 0
            }

        val funcType1 = lua_type(state, lua_upvalueindex1(1))
        if (funcType1 != LUA_TLIGHTUSERDATA) {
            luaL_error(state, "Closure upvalue 1 must be light user data (got type $funcType1)")
            return@staticCFunction 0
        }
        val funcPtr = state.readLightUserData(lua_upvalueindex1(1))
            ?: run {
                luaL_error(state, "Closure upvalue 1 is null")
                return@staticCFunction 0
            }
        val func = funcPtr.asStableRef<LuaFunction>().get()

        // lua_gettop(L) returns the number of values above L->ci->func on the
        // stack. For a direct function call `f(a,b,c,d)` that's 4; for a
        // userdata-with-__call call `obj(a,b,c,d)` that's 5 — Lua's
        // tryfuncTM shifts the userdata into the args slot before invoking
        // the metamethod. We filter that prepended userdata out below using
        // the AC_CLOSURE_PTR sentinel.
        val count = lua_gettop(state)
        val args = (1..count).mapNotNull { idx ->
            val arg = ctx.readValue(idx, true)
            if (arg is LuaValue.UserData && arg.ptr == AC_CLOSURE_PTR) {
                null
            } else {
                arg
            }
        }
        lua_pop(state, count)

        val result = func.call(args)
        result.forEach { ctx.pushValue(it) }
        result.size
    } catch (e: Throwable) {
        luaL_error(state, "$e")
        0
    }
}

/**
 * __gc metamethod for [ObjectContainer]'s closures. Invoked by Lua when a
 * closure (LuaValue.FunctionValue) is collected. Receives the closure value at
 * the top of the stack; recovers the function pointer from the closure's
 * upvalue and releases the StableRef.
 */
@OptIn(ExperimentalNativeApi::class)
val closureGc: lua_CFunction = staticCFunction { state ->
    try {
        if (state == null) return@staticCFunction 0
        val funcType1 = lua_type(state, lua_upvalueindex1(1))
        if (funcType1 != LUA_TLIGHTUSERDATA) {
            // Not our closure — silent no-op so Lua's GC walk doesn't crash on
            // closures we didn't create.
            return@staticCFunction 0
        }
        val funcPtr = state.readLightUserData(lua_upvalueindex1(1))
            ?: return@staticCFunction 0
        funcPtr.asStableRef<LuaFunction>().dispose()
        0
    } catch (e: Throwable) {
        luaL_error(state, "$e")
        0
    }
}

val userdataGc: lua_CFunction = staticCFunction { state ->
    try {
        if (state == null) return@staticCFunction 0
        check(lua_gettop(state) == 1) { "Invalid arguments to userdataGc (top=${lua_gettop(state)})" }
        val ptr = Heap.getPtrFromPtr(state.readUserData(-1))
        if (ptr != null) {
            ptr.asStableRef<LuaContext>().dispose()
        }
        0
    } catch (e: Throwable) {
        luaL_error(state, "Can't destroy userdata: $e")
        0
    }
}
