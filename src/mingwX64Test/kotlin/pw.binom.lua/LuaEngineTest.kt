package pw.binom.lua

import kotlinx.cinterop.pointed
import kotlinx.cinterop.usePinned
import platform.internal_lua.*
import kotlin.test.Test
import kotlin.test.assertEquals

class LuaEngineTest {

    @Test
    fun pushValue() {
//        index2value
        val e = LuaEngine()
        val o = ObjectContainer()

        e["test"] = o.makeClosure {
            emptyList()
        }

        lua_getglobal(e.state, "test")
        val vv = klua_get_value(e.state, 1)
        klua_push_value(e.state, klua_get_value(e.state, 1))
        klua_isvalid(e.state, klua_get_value(e.state, 1))
        e.state.printStack("after push function to stack")
    }

    @Test
    fun stackCleanDuringCall() {
        val e = LuaEngine()
        val o = ObjectContainer()
        var stackSize = 0

        e["test"] = o.makeClosure {
            assertEquals(3, it.size)
            stackSize = lua_gettop(e.state)
            emptyList()
        }

        e.eval("test(1,2,3)")
        assertEquals(0, stackSize)
    }
}