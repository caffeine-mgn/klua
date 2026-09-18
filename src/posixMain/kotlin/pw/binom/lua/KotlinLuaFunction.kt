@file:OptIn(ExperimentalForeignApi::class)

package pw.binom.lua

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import platform.internal_lua.LUA_TUSERDATA
import platform.internal_lua.luaL_error
import platform.internal_lua.lua_CFunction
import platform.internal_lua.lua_gettop
import platform.internal_lua.lua_type

/**
 * __gc metamethod for any userdata that owns a [kotlinx.cinterop.StableRef].
 *
 * Lua passes the userdata as self (idx 1) when its GC fires. We read the
 * userdata's payload — which we always set to a raw StableRef CPointer — and
 * dispose it.
 *
 * One cfunction serves all AC values, regardless of whether they wrap a Kotlin
 * object via [LuaEngine.createAC] or a [LuaFunction] via [LuaEngine.createACClosure].
 */
val userdataGc: lua_CFunction = staticCFunction { state ->
    try {
        if (state == null) return@staticCFunction 0
        if (lua_gettop(state) < 1) return@staticCFunction 0
        if (lua_type(state, 1) != LUA_TUSERDATA) return@staticCFunction 0
        val ptr = state.readUserData(1)
        if (ptr != null) {
            ptr.asStableRef<Any>().dispose()
        }
        0
    } catch (e: Throwable) {
        // __gc must not longjmp out of arbitrary call frames; swallow.
        0
    }
}

/**
 * C entry-point for any plain Lua closure created by
 * [ObjectContainer.makeClosure]. Reads the [kotlinx.cinterop.LuaFunction]
 * reference from upvalue(1) — a [kotlinx.cinterop.StableRef] holding the
 * Kotlin lambda.
 *
 * The [LuaContext] is recovered via [LuaContextRegistry] because Lua/Native
 * gives us no way to attach arbitrary userdata to a lua_State, so we maintain
 * a (state → context) map registered when the engine is constructed.
 *
 * The args are read directly from indices 1..count — there is no userdata
 * "self" prefix because plain closures created via makeClosure are not
 * attached to a userdata. AC-closures (the userdata-with-`__call` form
 * produced by [LuaEngine.createACClosure]) use a separate cfunction,
 * [AC_CLOSURE_FUNCTION], because Lua's tryfuncTM auto-prepends the userdata
 * as idx 1.
 */
val CLOSURE_FUNCTION: lua_CFunction = staticCFunction { state ->
    try {
        if (state == null) return@staticCFunction 0
        val ctx = LuaContextRegistry.lookup(state)
            ?: return@staticCFunction 0

        val funcPtr = state.readLightUserData(lua_upvalueindex1(1))
            ?: return@staticCFunction 0
        val func = funcPtr.asStableRef<LuaFunction>().get()

        val count = lua_gettop(state)
        val args = (1..count).map { ctx.readValue(it, true) }
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
 * C entry-point for any Lua closure installed as a userdata's `__call`
 * metamethod by [LuaEngine.createACClosure]. Reads the wrapped Kotlin
 * [LuaFunction] from the userdata's payload (idx 1) and looks up arg 1..
 * Without upvalues the closure has nothing to dangle when Lua's __gc
 * disposes the userdata's payload.
 *
 * Pre-fix: AC closures shared a StableRef between the closure's upvalue AND
 * the userdata payload; Lua's __gc would dispose it via the payload, leaving
 * the closure's upvalue pointing at freed memory. Splitting into a no-upvalue
 * closure + AC-dedicated cfunction closes that UAF.
 */
val AC_CLOSURE_FUNCTION: lua_CFunction = staticCFunction { state ->
    try {
        if (state == null) return@staticCFunction 0
        val ctx = LuaContextRegistry.lookup(state)
            ?: return@staticCFunction 0
        if (lua_type(state, 1) != LUA_TUSERDATA) return@staticCFunction 0

        // idx 1 is the userdata (self); its payload is a StableRef<LuaFunction>.
        val userDataPtr = state.readUserData(1) ?: return@staticCFunction 0
        val func = userDataPtr.asStableRef<Any>().get() as? LuaFunction
            ?: return@staticCFunction 0

        val count = lua_gettop(state)
        // Args start at idx 2 (after the self userdata). Strip the explicit
        // count check because for a plain `__call(self)` there are no extra
        // args; with at least one extra arg we read 2..count.
        val args = if (count >= 2) (2..count).map { ctx.readValue(it, true) } else emptyList()
        lua_pop(state, count)

        val result = func.call(args)
        result.forEach { ctx.pushValue(it) }
        result.size
    } catch (e: Throwable) {
        luaL_error(state, "$e")
        0
    }
}
