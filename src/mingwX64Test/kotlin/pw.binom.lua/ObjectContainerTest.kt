package pw.binom.lua

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObjectContainerTest {
    @Test
    fun makeClosureTest() {
        val m = ObjectContainer()
        val myFunc = m.makeClosure {
            // Do nothing
            emptyList()
        }
        assertEquals(CLOSURE_FUNCTION, myFunc.ptr)
        assertEquals(1, myFunc.upvalues.size)
    }

    @Test
    fun getClosureTest() {
        val m = ObjectContainer()
        val func = LuaFunction {
            emptyList()
        }
        val closure = m.makeClosure(func)
        assertEquals(func, m.getClosure(closure))
        assertEquals(func, m.get(closure.upvalues[0].checkedLightUserdata()))
        assertTrue(m.remove(func))
        assertNull(m.getClosure(closure))
    }
}
