package pw.binom.lua

import kotlinx.cinterop.ExperimentalForeignApi
import platform.internal_lua.luaL_newstate
import platform.internal_lua.lua_close
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
class LuaCtx private constructor(val state: LuaState) {
    constructor() : this(luaL_newstate() ?: throw RuntimeException("Can't create Lua State"))
    private val closable = createCleaner(state){
        lua_close(it)
    }
}