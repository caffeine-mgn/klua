package pw.binom.lua

import kotlin.test.Test
import kotlin.test.assertEquals


class RefTest : AbstractTest() {
    @Test
    fun pushGetReference() = start {
        val e = LuaEngine()
        e.ll.pushValue(LuaValue.TableValue())
        assertEquals(LUA_TTABLE1, e.ll.type(-1))
        val tablePtr = LUALIB_INSTANCE.lua_topointer1(e.ll.state, -1)!!.toLong1()
        val ref = e.ll.makeRef()
        assertEquals(0, LUALIB_INSTANCE.lua_gettop1(e.ll.state))
        e.ll.pushRef(ref)
        assertEquals(LUA_TTABLE1, e.ll.type(-1))
        assertEquals(tablePtr, LUALIB_INSTANCE.lua_topointer1(e.ll.state, -1)!!.toLong1())
    }
}