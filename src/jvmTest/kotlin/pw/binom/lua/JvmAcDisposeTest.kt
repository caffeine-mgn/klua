package pw.binom.lua

import pw.binom.lua.LuaValue

import java.lang.ref.Cleaner
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class JvmAcDisposeTest {

    @Test
    fun manualDisposeWorks() {
        val engine = LuaEngine()
        val container = ObjectContainer()
        val baseline = StaticRefs.size
        engine["create"] = container.makeClosure {
            listOf(engine.createAC("payload"))
        }
        val ud = engine.createAC("disposable-payload")
        val afterCreate = StaticRefs.size
        assertTrue(afterCreate > baseline, "createAC must add a StaticRefs entry")
        // Explicit dispose() — equivalent to what the Cleaner action does.
        ud.dispose()
        assertTrue(StaticRefs.size < afterCreate, "Manual dispose must drop StaticRefs entry")
    }

    @Test
    fun cleanerSmokeTest() {
        var cleaned = false
        val cleaner = Cleaner.create()
        fun makeLeak(): Any {
            val obj = Any()
            cleaner.register(obj) { cleaned = true }
            return obj
        }
        makeLeak()
        for (i in 1..50) {
            System.gc()
            Thread.sleep(20)
            if (cleaned) break
        }
        assertTrue(cleaned, "Cleaner must run when registered object is unreachable")
    }

    @Test
    fun autoCleanUserdataGetsDisposedByLuaGc() {
        val engine = LuaEngine()
        val container = ObjectContainer()
        val baseline = StaticRefs.size
        engine["create"] = container.makeClosure {
            listOf(engine.createAC("payload"))
        }
        engine.eval(
            """
            local ud = create()
            ud = nil
            """.trimIndent()
        )
        // Now the closure body has run and createAC has been invoked; the
        // Kotlin UserData wrapper is dropping out of scope, the JVM Cleaner
        // will eventually call dispose() which drops the registry entry and
        // StaticRefs. We sample StaticRefs.size across the GC + Lua GC loop
        // and verify the size has dropped back to the baseline — i.e. all
        // transient entries introduced by the test have been disposed.
        var peak = StaticRefs.size
        for (i in 1..50) {
            System.gc()
            System.runFinalization()
            Thread.sleep(20)
            val now = StaticRefs.size
            peak = maxOf(peak, now)
            if (now <= baseline) break
        }
        val afterGc = StaticRefs.size
        assertTrue(afterGc <= baseline,
            "After GC+collectgarbage, StaticRefs.size must return to baseline " +
            "(or below) — current=$afterGc, baseline=$baseline, peak=$peak. " +
            "An entry from the AC userdata was not disposed by Lua's __gc.")
    }

    /**
     * Regression guard for the TableRef / FunctionRef leak that existed before
     * commit 595d8b4-2: the `luaL_ref` in `readValueAt` was never balanced by
     * a `luaL_unref`, so every read of a table/function from Lua added an
     * entry to LUA_REGISTRYINDEX that lived for the entire JVM lifetime.
     *
     * We loop read+drop 1000 times and assert that the registry size, measured
     * via [LuaNative.registrySize], returns to baseline. Without the Cleaner
     * the registry grows linearly (1000 entries per loop); with the Cleaner
     * the growth is bounded by ~tens (the engine's own keepalive refs).
     */
    @Test
    fun tableAndFunctionRefsDoNotLeakIntoRegistry() {
        val engine = LuaEngine()
        val context = engine.ll
        val baseline = LuaNative.registrySize(context.state)
        // Generate 1000 unique tables/closures through Lua and drop the Kotlin
        // references immediately.
        repeat(1_000) { i ->
            engine.eval("local t = { idx = $i }; local f = function() return $i end")
            // Read each one back through the bridge, dropping the Kotlin
            // reference after use so the Cleaner can run.
            val globalT = engine["t"]
            val globalF = engine["f"]
            assertNotNull(globalT)
            assertNotNull(globalF)
            // Clear the Lua-side globals so they're not held by the registry
            // via _ENV either; the table+closure would stay reachable
            // through `t`/`f` even after we drop the Kotlin refs.
            engine.eval("t = nil; f = nil")
        }
        // Run a JVM + Lua GC cycle to let Cleaner actions fire.
        for (pass in 1..50) {
            System.gc()
            System.runFinalization()
            engine.eval("collectgarbage('collect')")
            Thread.sleep(20)
            val now = LuaNative.registrySize(context.state)
            if (now <= baseline + 50) break  // small slack for engine-internal keepalive
        }
        val after = LuaNative.registrySize(context.state)
        assertTrue(after <= baseline + 50,
            "LUA_REGISTRYINDEX grew under tight read+drop loop " +
            "(baseline=$baseline, after=$after, delta=${after - baseline}). " +
            "Each read of a table/function from Lua leaks a registry slot — " +
            "the Cleaner pattern on TableRef/FunctionRef is broken.")
    }
}

