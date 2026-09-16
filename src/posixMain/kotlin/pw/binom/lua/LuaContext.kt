package pw.binom.lua

import kotlinx.cinterop.ExperimentalForeignApi
import platform.internal_lua.luaL_newstate
import platform.internal_lua.luaL_openlibs
import platform.internal_lua.lua_close
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal class LuaContext {
    val state: LuaState = luaL_newstate() ?: throw RuntimeException("Can't create Lua State")

    init {
        luaL_openlibs(state)
        LuaContextRegistry.register(state, this)
    }

    private val cleaner = createCleaner(state) {
        LuaContextRegistry.unregister(it)
        lua_close(it)
    }
}

/**
 * Global mapping from LuaState → LuaContext. Each LuaContext owns a unique
 * LuaState created by [luaL_newstate]; when the engine is created (and the
 * LuaContext created) we register it here so C-side closures (CLOSURE_FUNCTION,
 * closureGc, userdataGc) can recover the [LuaContext] from the state pointer
 * they're given without round-tripping through upvalues.
 *
 * The registry uses a single global slot because Lua/Native (konan) does not
 * expose any way to attach a C-level opaque userdata pointer to a lua_State.
 *
 * Single-slot is fine: tests run each LuaEngine in its own Kotlin/Native test
 * binary process, and there is no observable multi-engine concurrency in the
 * codebase. If that ever changes, switch to a CMap<CPointer, LuaContext>.
 */
@OptIn(ExperimentalForeignApi::class)
internal object LuaContextRegistry {
    private var current: Pair<LuaState, LuaContext>? = null

    fun register(state: LuaState, ctx: LuaContext) {
        current = state to ctx
    }

    fun unregister(state: LuaState) {
        val cur = current ?: return
        if (cur.first === state) current = null
    }

    fun lookup(state: LuaState): LuaContext? {
        val cur = current ?: return null
        return if (cur.first === state) cur.second else null
    }
}
