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
    }

    private val cleaner = createCleaner(state) {
        lua_close(it)
    }
}
