package pw.binom.lua

import java.lang.ref.Cleaner
import kotlin.test.Test
import kotlin.test.assertTrue

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
        val afterCreate = StaticRefs.size
        engine.eval(
            """
            local ud = create()
            ud = nil
            """.trimIndent()
        )
        var afterGc = StaticRefs.size
        for (i in 1..50) {
            System.gc()
            System.runFinalization()
            Thread.sleep(20)
            afterGc = StaticRefs.size
            if (afterGc < afterCreate) break
        }
        assertTrue(afterGc < afterCreate,
            "Expected StaticRefs.size to decrease after JVM GC + Lua collectgarbage " +
            "(Lua __gc must dispose the userdata entry). baseline=$baseline " +
            "afterCreate=$afterCreate afterGc=$afterGc — __gc is not firing.")
    }
}

