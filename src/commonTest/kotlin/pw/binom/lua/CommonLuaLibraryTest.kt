package pw.binom.lua

import kotlin.test.*

/**
 * Tests for per-library standard-library selection ([LuaLibrary],
 * [LuaEngine.openLibs]). A fresh engine must be bare; [LuaLibrary.SAFE]
 * must expose only the sandbox-friendly subset.
 */
class CommonLuaLibraryTest {

    @Test
    fun defaultIsBare() {
        LuaEngine().use { e ->
            assertTrue(e["os"].isNil, "os must be absent by default")
            assertTrue(e["io"].isNil, "io must be absent by default")
            assertTrue(e["package"].isNil, "package must be absent by default")
            assertTrue(e["debug"].isNil, "debug must be absent by default")
            assertTrue(e["string"].isNil, "string must be absent by default")
            assertTrue(e["math"].isNil, "math must be absent by default")
        }
    }

    @Test
    fun safeSubsetLoadsOnlySelected() {
        LuaEngine(LuaLibrary.SAFE).use { e ->
            assertFalse(e["string"].isNil, "string must be present in SAFE mode")
            assertFalse(e["table"].isNil, "table must be present in SAFE mode")
            assertFalse(e["math"].isNil, "math must be present in SAFE mode")
            assertFalse(e["coroutine"].isNil, "coroutine must be present in SAFE mode")
            assertFalse(e["utf8"].isNil, "utf8 must be present in SAFE mode")
            assertTrue(e["os"].isNil, "os must be absent in SAFE mode")
            assertTrue(e["io"].isNil, "io must be absent in SAFE mode")
            assertTrue(e["package"].isNil, "package must be absent in SAFE mode")
            assertTrue(e["debug"].isNil, "debug must be absent in SAFE mode")
        }
    }

    @Test
    fun allLoadsEverything() {
        LuaEngine(LuaLibrary.ALL).use { e ->
            assertFalse(e["os"].isNil)
            assertFalse(e["io"].isNil)
            assertFalse(e["package"].isNil)
            assertFalse(e["debug"].isNil)
            assertFalse(e["string"].isNil)
            assertFalse(e["math"].isNil)
            assertFalse(e["table"].isNil)
            assertFalse(e["coroutine"].isNil)
            assertFalse(e["utf8"].isNil)
        }
    }

    @Test
    fun openLibsAfterConstruction() {
        LuaEngine().use { e ->
            assertTrue(e["math"].isNil)
            e.openLibs(setOf(LuaLibrary.MATH))
            assertFalse(e["math"].isNil, "math must be loaded after openLibs")
            assertTrue(e["string"].isNil, "string must still be absent")
        }
    }

    @Test
    fun safeSubsetCanRunBaseCode() {
        LuaEngine(LuaLibrary.SAFE).use { e ->
            val r = e.eval("return tostring(1 + 2)")
            assertEquals("3", r[0].checkedString())
        }
    }
}
