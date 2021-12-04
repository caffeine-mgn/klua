package pw.binom.lua

import platform.internal_lua.LUA_TFUNCTION
import platform.internal_lua.LUA_TTABLE
import platform.internal_lua.lua_gettop
import platform.internal_lua.lua_type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LuaValueTest {

    @Test
    fun pushClosureTest() {
        val e = LuaEngine()
        val m = ObjectContainer()
        val myFunc = m.makeClosure {
            emptyList()
        }
        e.ll.pushValue(myFunc)
        assertEquals(1, lua_gettop(e.state))
        assertEquals(LUA_TFUNCTION, lua_type(e.state, 1))
    }

    @Test
    fun pushTableTest() {
        val e = LuaEngine()
        val metatable = LuaValue.of(mapOf(2.lua to 3.lua))
        val table = LuaValue.of(mapOf(1.lua to 2.lua), metatable)
        e.ll.pushValue(table)
        assertEquals(1, lua_gettop(e.state))
        assertEquals(LUA_TTABLE, lua_type(e.state, 1))
    }

    @Test
    fun readTableWithMetatable2() {
        val e = LuaEngine()
        val metatable = LuaValue.of(mapOf(2.lua to 3.lua))
        val table = LuaValue.of(mapOf(1.lua to 2.lua), metatable)
        e.ll.pushValue(table)
        e.ll.readValue(1,false)
        assertEquals(1, lua_gettop(e.state))
    }

    @Test
    fun readTableWithMetatable1() {
        val e = LuaEngine()
        val o = ObjectContainer()
        e["test"] = o.makeClosure {
            assertEquals(0, lua_gettop(e.state))
            println("->${it[0]}")
            emptyList()
        }
        e["dump"] = o.makeClosure {
            println("pp->${it[0]}")
            emptyList()
        }
        e.eval(
            """
            function aa()
              print('aasd')
            end
            
            function set_value(t)
              t.oo='123'
            end
            
            function cc()
              local v = {}
              set_value(v)
              dump(v)
            end
            --cc()
            
            o = {}
            o.ccc=aa
            test(o)
        """
        )
    }

    @Test
    fun readTableWithMetatable() {
        val e = LuaEngine()
        val o = ObjectContainer()
        e["my_func"] = o.makeClosure {
            assertEquals(0, lua_gettop(e.state))
            println(it[0])
            emptyList()
        }
        val table = e.eval(
            """
           function ololo()
             print('ololo called')
           end
--           m = {}
--           i = {a='v'}
--           m.__call = ololo
--           m.__index = i
           o = {}
           o.ccc=ololo
--           setmetatable(o, m)
           print('o=', o)
           my_func(o)
        """
        )
//        assertEquals(0, lua_gettop(e.state))
//        assertEquals(1, table.size)
//        println("table=$table")
//        val t = table[0].checkedTable()
//        assertFalse(t.metatable.isNil)
    }

    @Test
    fun readClosureTest() {
        val e = LuaEngine()
        val m = ObjectContainer()
        val myFunc = m.makeClosure {
            emptyList()
        }
        e.ll.pushValue(myFunc)
        val func = e.ll.readValue(1,false)
        assertTrue(func is LuaValue.FunctionValue)
    }
}