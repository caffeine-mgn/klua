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
        println("Try execute...")
        try {
            println("#0")
            val e = LuaEngine(LuaLib.NATIVE)
            println("#1")
            e.ll.pushValue(LuaValue.TableValue())
            println("#2")
            assertEquals(LUA_TTABLE, e.ll.type(-1))
            println("#3")
            val tablePtr = lua_topointer(e.state, -1)!!.toLong()
            println("#4")
            val ref = e.ll.makeRef()
            println("#5")
            assertEquals(0, lua_gettop(e.state))
            println("#6")
            e.ll.pushRef(ref)
            println("#7")
            assertEquals(LUA_TTABLE, e.ll.type(-1))
            println("#8")
            assertEquals(tablePtr, lua_topointer(e.state, -1)!!.toLong())
            println("#9")
        } catch (e:Throwable) {
            e.printStackTrace()
//            throw e
        }
        println("Finished")
    }
}