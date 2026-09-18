package pw.binom.lua

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class CommonLuaValueTest : AbstractTest() {
    @Test
    fun readTable() = start {
        val e = makeEngine()
        e["test"] = LuaValue.of(mapOf(1.0.lua to 2.0.lua))
        val table = e["test"]
        val m = table.checkedTable()
            .toMap()
            .entries
            .associate { it.key.checkedNumber() to it.value.checkedNumber() }
        assertEquals(1, m.size)
        assertEquals(2.0, m[1.0])
    }

    /**
     * [LuaValue.Nil] should round-trip through Lua's globals as Lua nil.
     */
    @Test
    fun nilRoundTripTest() = start {
        val e = makeEngine()
        // Set a global to Nil and read it back from Lua side; expect Lua nil.
        e["x"] = LuaValue.Nil
        assertTrue(
            e.eval("return x == nil")[0].checkedBoolean(),
            "Kotlin Nil should round-trip as Lua nil")
        // Setting Lua nil should also produce Kotlin Nil.
        e.eval("x = nil")
        assertEquals(LuaValue.Nil, e["x"],
            "Lua-side nil assignment should produce Kotlin Nil")
    }

    /**
     * [LuaValue.LuaInt] round-trip — Kotlin Long values should become Lua
     * integers and read back as Kotlin Long (not Number). Both JVM and
     * posix currently widen Lua integers to Number on read for parity, so
     * the expected behaviour is "value() returns Double" — verify it.
     */
    @Test
    fun integerRoundTripTest() = start {
        val e = makeEngine()
        e["x"] = LuaValue.of(42L)
        // Lua-side type: integer values preserve the integer subtype on
        // the Lua side, so type(x) is "number" but lua_isinteger reports
        // true. Since Round 6/E15 the readValueAt dispatch distinguishes
        // them and returns LuaInt rather than Number.
        assertEquals("number", e.eval("return type(x)")[0].checkedString(),
            "LuaInt should look like a number from Lua side")
        // Kotlin-side read: round-trip preserves integer-ness, so the
        // returned value is a LuaInt with the original value.
        val readX = e["x"] as LuaValue.LuaInt
        assertEquals(42L, readX.value,
            "LuaInt round-trip should yield LuaInt with the same value")
        // Float-literal on Lua side comes back as Number — that's the
        // discriminator that makes the readValueAt split correct.
        e["y"] = LuaValue.of(3.14)
        val readY = e["y"] as LuaValue.Number
        assertEquals(3.14, readY.value,
            "Float round-trip should yield Number")
    }

    /**
     * Nested table round-trip — table-inside-table should preserve entries.
     */
    @Test
    fun nestedTableRoundTripTest() = start {
        val e = makeEngine()
        val nested = LuaValue.of(mapOf("inner".lua to "deep".lua))
        val outer = LuaValue.of(mapOf("n".lua to nested))
        e["root"] = outer
        assertEquals("deep",
            e.eval("return root.n.inner")[0].checkedString(),
            "nested table entries should be reachable through Lua path")
    }

    /**
     * [LuaValue.FunctionRef] read back from Lua side should still be callable.
     */
    @Test
    fun functionRefRoundTripTest() = start {
        val e = makeEngine()
        val o = ObjectContainer()
        var calls = 0
        val fn = o.makeClosure {
            calls++
            listOf("hello".lua)
        }
        // Store the closure as a global
        e["greet"] = fn
        // Read it back via Lua, then call from Lua side
        val result = e.eval("return greet()")
        assertEquals(1, calls, "Lua-side call of stored closure should fire")
        assertEquals("hello", result[0].checkedString(),
            "Lua-side call should round-trip the return value")
    }

    /**
     * `Boolean` round-trip via set/get (not just the eval literal already
     * covered by `booleanRoundTripTest`). Kotlin true / false should both
     * make it through unchanged when stored and retrieved.
     */
    @Test
    fun booleanGetSetRoundTripTest() = start {
        val e = makeEngine()
        e["t"] = LuaValue.of(true)
        e["f"] = LuaValue.of(false)
        assertTrue(e["t"].checkedBoolean(), "Kotlin true should round-trip")
        assertFalse(e["f"].checkedBoolean(), "Kotlin false should round-trip")
    }
}
