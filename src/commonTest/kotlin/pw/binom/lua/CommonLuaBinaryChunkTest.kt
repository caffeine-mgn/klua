package pw.binom.lua

import kotlin.test.*

/**
 * Tests for the engine-wide binary-chunk policy
 * ([LuaEngine]`(allowBinaryChunks = ...)`). With the default `false`, neither
 * [LuaEngine.eval] nor Lua's own `load` may load precompiled bytecode; with
 * `true` the historical behaviour is restored.
 */
class CommonLuaBinaryChunkTest {

    @Test
    fun evalRejectsBinaryChunkByDefault() {
        LuaEngine(setOf(LuaLibrary.BASE)).use { e ->
            // 0x1B is the first byte of Lua's binary-chunk signature. With
            // mode = "t" Lua must refuse it before parsing.
            assertFailsWith<LuaException> { e.eval("\u001bLuaJunk") }
        }
    }

    @Test
    fun loadRejectsBinaryChunksByDefault() {
        LuaEngine(LuaLibrary.SAFE).use { e ->
            val r = e.eval(
                """
                local d = string.dump(function() return 1 end)
                local f = load(d)
                return f == nil
                """.trimIndent(),
            )
            assertEquals(true, r[0].checkedBoolean())
        }
    }

    @Test
    fun loadAcceptsBinaryChunksWhenAllowed() {
        LuaEngine(LuaLibrary.SAFE, allowBinaryChunks = true).use { e ->
            val r = e.eval(
                """
                local d = string.dump(function() return 42 end)
                local f = load(d)
                return type(f)
                """.trimIndent(),
            )
            assertEquals("function", r[0].checkedString())
        }
    }

    @Test
    fun dumpedFunctionRunsWhenBinaryAllowed() {
        LuaEngine(LuaLibrary.SAFE, allowBinaryChunks = true).use { e ->
            val r = e.eval(
                """
                local f = load(string.dump(function() return 42 end))
                return f()
                """.trimIndent(),
            )
            assertEquals(42, r[0].checkedInt())
        }
    }
}
