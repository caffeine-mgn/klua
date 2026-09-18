package pw.binom.lua

import kotlinx.cinterop.ExperimentalForeignApi
import platform.internal_lua.luaL_newstate
import platform.internal_lua.luaL_openlibs
import platform.internal_lua.lua_close
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal class LuaContext(safeMode: Boolean = false) {
    val state: LuaState = luaL_newstate() ?: throw RuntimeException("Can't create Lua State")

    init {
        // NOTE: a fresh Lua state is intentionally bare — no standard
        // library is loaded by default. Call [LuaEngine.openStandardLibs]
        // to opt into the full stdlib for trusted Lua source.
        // The deprecated `safeMode` parameter is reserved for a future
        // hard-coded safe subset (base/string/table/math/utf8); the JVM
        // counterpart defaults to no-lib for parity until that's wired up.
        if (safeMode) {
            // TODO: load only base/string/table/math/utf8 via luaL_requiref
            // when a clean API for picking individual libs lands.
        }
        LuaContextRegistry.register(state, this)
    }

    private val cleaner = createCleaner(state) {
        LuaContextRegistry.unregister(it)
        lua_close(it)
    }

    /**
     * Loads every Lua 5.4 standard library into this context's state via
     * [luaL_openlibs]. See [pw.binom.lua.LuaEngine.openStandardLibs].
     */
    fun openStandardLibs() {
        luaL_openlibs(state)
    }
}

/**
 * Global mapping from LuaState → LuaContext. Each LuaContext owns a unique
 * LuaState created by [luaL_newstate]; when the engine is created (and the
 * LuaContext created) we register it here so C-side closures (CLOSURE_FUNCTION,
 * closureGc, userdataGc) can recover the [LuaContext] from the state pointer
 * they're given without round-tripping through upvalues.
 *
 * A multi-slot map is required (not the previous single-slot `current` Pair):
 * a second LuaEngine in the same process overwrites the slot, and the first
 * engine's callbacks then silently no-op in CLOSURE_FUNCTION or leak StableRefs
 * in userdataGc because `lookup(state_of_first) -> null`. Tests that spin up
 * multiple engines — and any service that scopes an engine per request — hit
 * this in production.
 */
@OptIn(ExperimentalForeignApi::class)
internal object LuaContextRegistry {
    // The previous implementation used a single-slot Pair<state, ctx> that any
    // second LuaEngine would silently overwrite, breaking every Kotlin callback
    // dispatched for the first engine. A multi-slot map is required.
    //
    // Concurrency: callback dispatch is single-threaded per engine on POSIX
    // (Lua 5.4's lua_State is not thread-safe), and engine construction is
    // typically serial in embeddings. Plain HashMap without explicit locking
    // is sufficient — multi-engine *lookup* happens on whichever thread the
    // engine's callback runs, but a single lua_State is owned by exactly one
    // thread. Concurrent LuaEngine *construction* from multiple threads is
    // the caller's contract to serialise.
    private val map = HashMap<LuaState, LuaContext>()

    fun register(state: LuaState, ctx: LuaContext) {
        map[state] = ctx
    }

    fun unregister(state: LuaState) {
        map.remove(state)
    }

    fun lookup(state: LuaState): LuaContext? = map[state]
}
