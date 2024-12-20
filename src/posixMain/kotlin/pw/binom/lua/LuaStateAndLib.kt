package pw.binom.lua

import kotlinx.cinterop.ExperimentalForeignApi
import platform.internal_lua.lua_close
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal class LuaStateAndLib(val state: LuaState) {
    val cleaner = createCleaner(state) {
        lua_close(it)
    }
}
