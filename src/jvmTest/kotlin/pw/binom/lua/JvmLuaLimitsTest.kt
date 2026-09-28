package pw.binom.lua

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM-only execution-limit tests: cross-thread cancellation and allocator
 * accounting. The instruction/timeout/memory semantics shared with the
 * Kotlin/Native backend live in [CommonLuaLimitsTest].
 */
class JvmLuaLimitsTest {

    @Test
    fun cancelFromAnotherThreadAborts() {
        LuaEngine(LuaLibrary.SAFE).use { e ->
            val failure = AtomicReference<Throwable?>()
            val started = CountDownLatch(1)
            val thread = Thread {
                try {
                    started.countDown()
                    e.eval("while true do end")
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
            thread.start()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            e.cancel()
            thread.join(10_000)
            assertTrue(!thread.isAlive, "eval did not stop after cancel()")
            val ex = failure.get()
            assertTrue(ex is LuaLimitException, "expected LuaLimitException, got $ex")
            assertEquals(LuaLimitKind.CANCELLED, ex.kind)
        }
    }

    @Test
    fun unlimitedEngineReportsNoReasonAndAccountsMemory() {
        LuaEngine(LuaLibrary.SAFE).use { e ->
            e.eval("local t = {} for i = 1, 1000 do t[i] = i end")
            val reason = e.ll.lastLimitReason()
            assertEquals(LuaLimitReason.NONE, reason)
            assertTrue(e.ll.usedMemory() > 0, "allocator should account the live state")
        }
    }
}
