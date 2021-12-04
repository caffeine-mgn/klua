package pw.binom.lua

import kotlinx.cinterop.pointed
import kotlinx.cinterop.usePinned
import platform.internal_lua.*
import kotlin.test.Test
import kotlin.test.assertEquals

class LuaEngineTest {

    @Test
    fun stackCleanDuringCall() {
        val e = LuaEngine()
        val o = ObjectContainer()
        var stackSize = 0

        e["test"] = o.makeClosure {
            assertEquals(3, it.size)
            stackSize = e.ll.lib.lua_gettop1(e.state)
            emptyList()
        }

        e.eval("test(1,2,3)")
        assertEquals(0, stackSize)
    }
}