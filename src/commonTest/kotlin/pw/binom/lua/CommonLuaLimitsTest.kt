package pw.binom.lua

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

/**
 * Execution-limit tests shared by every target (JVM via JNI and Kotlin/Native
 * via the same `klua_limits.c` core).
 *
 * Limits are armed per top-level eval/call. The hook runs every 10 000 Lua VM
 * instructions, so instruction limits are approximate to within one period.
 */
class CommonLuaLimitsTest {

    @Test
    fun instructionLimitAbortsInfiniteLoop() {
        LuaEngine(LuaLibrary.SAFE, LuaLimits(maxInstructions = 200_000)).use { e ->
            val ex = assertFailsWith<LuaLimitException> {
                e.eval("while true do end")
            }
            assertEquals(LuaLimitKind.INSTRUCTIONS, ex.kind)
        }
    }

    @Test
    fun timeoutAbortsInfiniteLoop() {
        LuaEngine(LuaLibrary.SAFE, LuaLimits(timeout = 200.milliseconds)).use { e ->
            val ex = assertFailsWith<LuaLimitException> {
                e.eval("while true do end")
            }
            assertEquals(LuaLimitKind.TIMEOUT, ex.kind)
        }
    }

    @Test
    fun memoryLimitAbortsGrowth() {
        LuaEngine(LuaLibrary.SAFE, LuaLimits(maxMemoryBytes = 512 * 1024)).use { e ->
            val ex = assertFailsWith<LuaLimitException> {
                e.eval("local t = {} for i = 1, 10000000 do t[i] = i end")
            }
            assertEquals(LuaLimitKind.MEMORY, ex.kind)
        }
    }

    @Test
    fun pcallCannotSwallowLimits() {
        LuaEngine(LuaLibrary.SAFE, LuaLimits(maxInstructions = 200_000)).use { e ->
            // The inner pcall catches the limit error, but the control block's
            // reason stays set, so the whole top-level operation must abort.
            val ex = assertFailsWith<LuaLimitException> {
                e.eval("local ok = pcall(function() while true do end end) return ok")
            }
            assertEquals(LuaLimitKind.INSTRUCTIONS, ex.kind)
        }
    }

    @Test
    fun unlimitedIsDefaultAndRunsNormally() {
        LuaEngine(LuaLibrary.SAFE).use { e ->
            val result = e.eval("local s = 0 for i = 1, 1000 do s = s + i end return s")
            assertEquals(500500L, (result.first() as LuaValue.LuaInt).value)
        }
    }

    @Test
    fun repeatedLimitAbortsLeaveEngineUsable() {
        // Each aborted operation must clean up its stack/error value and
        // re-arm the hook; otherwise a long-lived sandbox would degrade after
        // the first trip.
        LuaEngine(LuaLibrary.SAFE, LuaLimits(maxInstructions = 100_000)).use { e ->
            repeat(50) {
                assertFailsWith<LuaLimitException> { e.eval("while true do end") }
            }
            val result = e.eval("return 7")
            assertEquals(7L, (result.first() as LuaValue.LuaInt).value)
        }
    }
}
