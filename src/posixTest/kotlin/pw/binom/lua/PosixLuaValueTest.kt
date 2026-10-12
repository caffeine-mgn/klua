@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pw.binom.lua

import platform.internal_lua.LUA_TFUNCTION
import platform.internal_lua.LUA_TTABLE
import platform.internal_lua.lua_gettop
import platform.internal_lua.lua_type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * POSIX-специфичные проверки низкоуровневого push/read через `LuaContext`.
 * Перенесены из `src/mingwX64Test/` (там они не исполнялись ни в одном CI —
 * mingwX64Test не запускается на linux/macos) в `posixTest`, который
 * прогоняется на linuxX64Test и остальных POSIX-таргетах.
 * См. AUDIT-2026-10-12.md #14.
 */
class PosixLuaValueTest {

    @Test
    fun pushClosureTest() {
        val e = LuaEngine()
        val m = ObjectContainer()
        val myFunc = m.makeClosure { emptyList() }
        e.ll.pushValue(myFunc)
        assertEquals(1, lua_gettop(e.ll.state))
        assertEquals(LUA_TFUNCTION, lua_type(e.ll.state, 1))
    }

    @Test
    fun pushTableTest() {
        val e = LuaEngine()
        val metatable = LuaValue.of(mapOf(2.lua to 3.lua))
        val table = LuaValue.of(mapOf(1.lua to 2.lua), metatable)
        e.ll.pushValue(table)
        assertEquals(1, lua_gettop(e.ll.state))
        assertEquals(LUA_TTABLE, lua_type(e.ll.state, 1))
    }

    @Test
    fun readClosureTest() {
        val e = LuaEngine()
        val m = ObjectContainer()
        val myFunc = m.makeClosure { emptyList() }
        e.ll.pushValue(myFunc)
        val func = e.ll.readValue(1, false)
        assertTrue(func is LuaValue.FunctionValue)
    }
}
