package pw.binom.lua

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Регресс на `AUDIT-2026-10-12.md` #4: JVM `FunctionRef.toValue()` терял
 * callback id (возвращал `FunctionValue(0)`), а `ObjectContainer.removeClosure(FunctionRef)`
 * был захардкожен в `false` и не снимал bridge.
 */
class JvmFunctionRefTest {

    @Test
    fun toValueRecoversCallbackId() {
        val engine = LuaEngine().apply { openStandardLibs() }
        val oc = ObjectContainer()
        val value = oc.makeClosure { listOf("hi".lua) }
        val ref = engine.makeRef(value)
        assertEquals(
            value.callbackId,
            ref.toValue().callbackId,
            "FunctionRef.toValue() must preserve the callback id",
        )
    }

    @Test
    fun removeClosureByRefWorksAndReleasesBridge() {
        val engine = LuaEngine().apply { openStandardLibs() }
        val oc = ObjectContainer()
        val value = oc.makeClosure { emptyList() }
        engine["f"] = value
        val before = LuaNative.callbackCount
        val ref = engine.makeRef(value)
        assertTrue(oc.removeClosure(ref), "removeClosure(FunctionRef) must find the bridge")
        assertTrue(
            LuaNative.callbackCount < before,
            "removeClosure must unregister the JVM-global bridge, not leave it until container GC",
        )
    }

    @Test
    fun foreignFunctionRefToValueIsSafe() {
        val engine = LuaEngine().apply { openStandardLibs() }
        // A plain Lua function is not one of our trampoline closures, so its
        // upvalue 1 is absent/non-integer -> callback id 0, and removeClosure
        // must report "not ours" rather than touch anything.
        val ref = engine.eval("return function() return 1 end")[0] as LuaValue.FunctionRef
        assertEquals(0, ref.toValue().callbackId)
        assertFalse(ObjectContainer().removeClosure(ref))
    }
}
