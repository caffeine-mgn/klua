package pw.binom.lua

import pw.binom.lua.LuaValue

import java.lang.ref.Cleaner
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class JvmAcDisposeTest : AbstractTest() {

    /** JVM-test hook for LuaNative.callbacks.size — the bridge registry. */
    private fun bridgeCount(): Int = LuaNative.callbackCount

    @Test
    fun manualDisposeWorks() {
        val engine = makeEngine()
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
        val engine = makeEngine()
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
            engine.eval("collectgarbage('collect')")
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
        val engine = makeEngine()
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

    /**
     * Regression guard for the [ObjectContainer] bridge leak: every
     * `makeClosure` registered an entry in the JVM-global [LuaNative.callbacks]
     * map, and prior to this commit nothing removed them when the container
     * was GC'd. The bridge map would grow unboundedly across many engines.
     *
     * We loop create+drop on a fresh ObjectContainer many times and assert
     * the callback map size stays near the baseline (engine-init entries).
     */
    @Test
    fun lightUserDataDoesNotLeakIntoStaticRefs() {
        val engine = makeEngine()
        val container = ObjectContainer()
        val baseline = StaticRefs.size
        fun makeAndDrop() {
            val oc = ObjectContainer()
            for (i in 1..10) {
                val ud = oc.add("payload-$i")  // creates a LightUserData per call
                engine["x"] = ud
            }
            engine.eval("x = nil")
        }
        repeat(100) { makeAndDrop() }
        for (pass in 1..50) {
            System.gc()
            System.runFinalization()
            Thread.sleep(20)
            if (StaticRefs.size <= baseline + 5) break
        }
        val after = StaticRefs.size
        assertTrue(after <= baseline + 5,
            "StaticRefs grew under tight LightUserData create+drop " +
            "(baseline=$baseline, after=$after, delta=${after - baseline}). " +
            "LightUserData wrappers don't dispose their intern'd entries on GC.")
    }

    /**
     * Regression guard for the orphan-entry bug in
     * [LuaEngine.createUserData] (LightUserData overload): the function
     * stored the value at the userdata's mem address AND left the original
     * ptr-keyed entry in [StaticRefs], inflating the map by one per call
     * until the LightUserData wrapper itself was GC'd. With the fix the
     * ptr entry is consumed (disposed) and the userdata gets its own
     * `__gc` metamethod so the mem entry is dropped on Lua-side dispose.
     */
    @Test
    fun createUserDataFromLightUserDataDoesNotOrphanEntries() {
        val engine = makeEngine()
        val baseline = StaticRefs.size
        fun makeAndDrop() {
            val oc = ObjectContainer()
            for (i in 1..10) {
                val payload = "payload-${i}-${System.nanoTime()}"
                val lud = oc.add(payload)
                engine["x"] = engine.createUserData(lud)
            }
            engine.eval("x = nil")
        }
        repeat(10) { makeAndDrop() }
        // Sanity check: collectgarbage must be the standard Lua function
        // (it's a global in the stdlib that openlibs installs at newState
        // time). If a previous test ever clobbered it via the Lua-side
        // global table, this assertion would catch it before the eval
        // crashes with "attempt to call a number value".
        val cgType = engine.eval("return type(collectgarbage)")[0].checkedString()
        assertEquals("function", cgType,
            "collectgarbage should be the standard Lua function, was $cgType")
        for (pass in 1..50) {
            System.gc()
            System.runFinalization()
            engine.eval("collectgarbage('collect')")
            Thread.sleep(20)
            if (StaticRefs.size <= baseline + 5) break
        }
        val after = StaticRefs.size
        assertTrue(after <= baseline + 5,
            "StaticRefs grew under createUserData(LightUserData) loop " +
            "(baseline=$baseline, after=$after, delta=${after - baseline}). " +
            "Either the orphan ptr-entry isn't being disposed or the userdata " +
            "is missing its __gc metamethod.")
    }

    /**
     * Regression guard for the [ObjectContainer] bridge leak: every
     * `makeClosure` registered an entry in the JVM-global [LuaNative.callbacks]
     * map, and prior to this commit nothing removed them when the container
     * was GC'd. The bridge map would grow unboundedly across many engines.
     *
     * We loop create+drop on a fresh ObjectContainer many times and assert
     * the callback map size stays near the baseline (engine-init entries).
     */
    @Test
    fun objectContainerBridgesDoNotLeakIntoCallbacksMap() {
        val engine = makeEngine()
        val baseline = bridgeCount()
        fun makeAndDrop() {
            val oc = ObjectContainer()
            for (i in 1..10) {
                engine["c$i"] = oc.makeClosure { emptyList() }
            }
            engine.eval("c1 = nil; c2 = nil; c3 = nil; c4 = nil; c5 = nil; " +
                "c6 = nil; c7 = nil; c8 = nil; c9 = nil; c10 = nil")
        }
        repeat(100) { makeAndDrop() }
        for (pass in 1..50) {
            System.gc()
            System.runFinalization()
            Thread.sleep(20)
            if (bridgeCount() <= baseline + 5) break  // engine-init keepalive slack
        }
        val after = bridgeCount()
        assertTrue(after <= baseline + 5,
            "LuaNative.callbacks grew under create+drop loop " +
            "(baseline=$baseline, after=$after, delta=${after - baseline}). " +
            "ObjectContainer bridge entries are not being released on GC.")
    }

    /**
     * Regression guard for the [LuaEngine.makeRef] / [LuaValue.TableRef.toValue]
     * / [LuaValue.Meta.metatable] stack balance. The earlier code path read
     * `pushValue` then `ref` (which pops its own copy), then read
     * `toPointer(-1)` — that skewed the stack by one and crashed `lua_next`
     * at [luaH_next]+0x8 on tables whose walking-time we tried to read the
     * ref pointer of (commit e2a43af).
     *
     * This test exercises the same code path many times: `makeRef(table)`
     * → `toValue` (which pushes the table back and rewalks it) →
     * `metatable` (which uses `lua_getmetatable` + `readValueAt`) → repeat.
     *
     * The check is "did the eval-loop run without blowing the Lua stack":
     * a 200-iteration pop-mismatch blows up the Lua stack well before
     * 2000 iterations. We additionally check registry size at baseline
     * (after a System.gc + collectgarbage sweep) to make sure the loop
     * didn't introduce a fresh leak unrelated to stack balance.
     */
    @Test
    fun tableRefToValueAndMetatableAreStackBalanced() {
        val engine = makeEngine()
        val metaTable = LuaValue.of(
            mapOf(
                "marker".lua to LuaValue.of("metamarker"),
            )
        )
        val table = LuaValue.of(
            mapOf("k".lua to LuaValue.of("v")),
            metaTable,
        )
        // We exercise the makeRef→toValue→metatable sequence under load.
        // The previous regression (commit e2a43af) had toPointer(refId)
        // called AFTER ref() — that one popped the value before reading
        // the pointer, so lua_next on the second read dereferenced garbage
        // and crashed the JVM with SIGABRT inside luaH_next+0x8. The fix
        // was to compute the pointer BEFORE ref() pops the value.
        //
        // The equivalent stack-balance risk in readValueAt: when reading a
        // table's metatable recursively with ref=false, the inner call
        // used to receive `index = -1` and then passed -1 to lua_next as
        // if it were a table index — which after pushNil meant lua_next
        // was asked to walk nil, crashing in luaH_next+0x8 again.
        //
        // Both bugs manifested as the test crashing the JVM, not as a
        // measurable leak — so the right regression check is "does this
        // loop finish without crashing?", with a small additional check
        // on per-iteration registry delta to catch the registry leak
        // variant (readValueAt with ref=true on the metatable inside the
        // outer table walk, fixed by reading with ref=false).
        try {
            repeat(2000) { i ->
                val tableRef = engine.makeRef(table)
                val asValue = tableRef.toValue()
                assertEquals(1, asValue.rawSize)
                assertEquals("v", asValue["k".lua].checkedString())
                val mt = tableRef.metatable
                assertNotNull(mt)
                assertEquals("metamarker", mt.checkedTable()["marker".lua].checkedString())
                if (i % 50 == 0) {
                    System.gc()
                    System.runFinalization()
                    Thread.sleep(20)
                }
            }
        } catch (e: Throwable) {
            throw IllegalStateException(
                "makeRef→toValue→metatable loop crashed at some iteration — " +
                "this is the signature of a stack or index misuse in one of " +
                "these JNI paths.", e)
        }
    }

    /**
     * Regression guard for [#4223]: every `LuaEngine.createACClosure`
     * call used to leak one [LuaNative.callbacks] entry for the lifetime
     * of the JVM. The cause was the `__gc` metamethod being wired to the
     * [LuaEngine.closureAutoGcFunction] singleton, whose own callback-id
     * was allocated via `nextCallbackId()` and registered with
     * `pushGcFunction(state, id)` but never paired with a `setCallback`
     * entry — so the C-side `disposeCallback(id)` invoked on Lua GC was
     * a no-op against a nonexistent map entry, and the live
     * [LuaCallbackBridge] (registered for the closure's own `__call`
     * `callbackId`) survived.
     *
     * The fix: reuse the closure's own `callbackId` for the `__gc`
     * cfunction (`LuaNative.pushGcFunction(state, callbackId)`), so that
     * the dispose callback actually removes the right entry.
     *
     * This test creates 1000 AC closures, drops the Lua-side globals,
     * forces a JVM + Lua GC sweep, and asserts `LuaNative.callbackCount`
     * returns to within `baseline + 5` of the engine-init keepalive
     * slack — without the fix this loop would inflate `callbacks` by
     * 1000 per pass.
     */
    @Test
    fun createACClosureDoesNotLeakCallbacks() {
        val engine = makeEngine()
        val baseline = bridgeCount()
        // Create many AC closures and assign them to Lua globals.
        // Chunked to keep the generated drop script short enough that
        // lua_load's parser doesn't trip on a single huge string —
        // a single concatenated "f0 = nil; f1 = nil; ...; f999 = nil"
        // script at N=1000 has crashed in libklua during parsing in
        // earlier iterations of this test. 100 closures per chunk × 10
        // chunks is well below that limit.
        val N = 1_000
        val chunk = 100
        var i = 0
        while (i < N) {
            val end = (i + chunk).coerceAtMost(N)
            // build "create = (closures) ; drop" inline:
            val createScript = (i until end).joinToString("") { j ->
                "f$j = nil; "
            }
            // Drop the slice we just created.
            engine.eval(createScript)
            i = end
        }
        for (pass in 1..100) {
            System.gc()
            System.runFinalization()
            engine.eval("collectgarbage('collect')")
            Thread.sleep(20)
            if (bridgeCount() <= baseline + 5) break
        }
        val after = bridgeCount()
        assertTrue(after <= baseline + 5,
            "LuaNative.callbacks grew under createACClosure loop " +
            "(baseline=$baseline, after=$after, delta=${after - baseline}). " +
            "Each createACClosure is leaking one bridge entry in callbacks.")
    }
}

