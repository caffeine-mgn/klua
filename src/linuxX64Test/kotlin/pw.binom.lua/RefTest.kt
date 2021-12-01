package pw.binom.lua

import kotlinx.cinterop.toLong
import platform.internal_lua.LUA_TTABLE
import platform.internal_lua.lua_gettop
import platform.internal_lua.lua_topointer
import kotlin.test.Test
import kotlin.test.assertEquals

class RefTest {

    @Test
    fun pushGetReference() {
        val e = LuaEngine()
        e.state.pushValue(LuaValue.TableValue())
        assertEquals(LUA_TTABLE, e.state.type(-1))
        val tablePtr = lua_topointer(e.state, -1)!!.toLong()
        val ref = e.state.makeRef()
        assertEquals(0, lua_gettop(e.state))
        e.state.pushRef(ref)
        assertEquals(LUA_TTABLE, e.state.type(-1))
        assertEquals(tablePtr, lua_topointer(e.state, -1)!!.toLong())
    }
}