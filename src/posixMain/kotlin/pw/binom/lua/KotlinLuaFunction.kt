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
 *
 * Replaces the broken pre-existing `userdataGc` (which tried to dispose a
 * `StableRef<LuaContext>` from a payload that never held one — the value was
 * a `StableRef<Any>` of whatever the user actually passed).
 */
val userdataGc: lua_CFunction = staticCFunction { state ->
    try {
        if (state == null) return@staticCFunction 0
        if (lua_gettop(state) < 1) return@staticCFunction 0
        if (lua_type(state, 1) != LUA_TUSERDATA) return@staticCFunction 0
        val ptr = Heap.getPtrFromPtr(state.readUserData(1))
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
 * C entry-point for any Lua closure created by [ObjectContainer.makeClosure]
 * or [LuaEngine.createACClosure].
 *
 * Upvalue layout:
 *   upvalue(1) = LUA_TLIGHTUSERDATA holding a StableRef<LuaFunction> pointer.
 *
 * The [LuaContext] is recovered via [LuaContextRegistry] because Lua/Native
 * gives us no way to attach arbitrary userdata to a lua_State, so we maintain
 * a global (state → context) pointer set when the engine is constructed.
 *
 * Two calling conventions are supported:
 *  - Direct closure call: `f(a,b,c)`. Args are at idx 1..N, no userdata prefix.
 *  - Userdata-with-`__call`: `obj(a,b,c)`. Lua's tryfuncTM shifts `obj` into
 *    the args slot before invoking the metamethod, so idx 1 holds the
 *    userdata (auto-prepended self). We detect and strip that arg by type
 *    check rather than by sentinel value — the previous AC_CLOSURE_PTR
 *    sentinel was redundant with the LUA_TUSERDATA check and got removed.
 */
val CLOSURE_FUNCTION: lua_CFunction = staticCFunction { state ->
    try {
        if (state == null) return@staticCFunction 0
        val ctx = LuaContextRegistry.lookup(state)
            ?: return@staticCFunction 0

        val funcPtr = state.readLightUserData(lua_upvalueindex1(1))
            ?: return@staticCFunction 0
        val func = funcPtr.asStableRef<LuaFunction>().get()

        // lua_gettop returns arg count as seen by this cfunction. For a direct
        // call f(a,b,c) that's 3. For obj(a,b,c) over __call metamethod that's
        // 4 — Lua's tryfuncTM pushes the userdata into the args. Strip the
        // userdata by type check; no sentinel value needed.
        val count = lua_gettop(state)
        val args = if (count > 0 && lua_type(state, 1) == LUA_TUSERDATA) {
            if (count > 1) (2..count).map { ctx.readValue(it, true) } else emptyList()
        } else {
            (1..count).map { ctx.readValue(it, true) }
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
