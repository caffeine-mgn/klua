package pw.binom.lua

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Регресс на `AUDIT-2026-10-12.md` #1: id мостов [ObjectContainer.makeClosure]
 * и [LuaEngine.createACClosure] не должны пересекаться — оба регистрируют свой
 * bridge в одной общей карте `LuaNative.callbacks`. До фикса два счётчика
 * стартовали с 1, и обычный closure вызывал лямбду AC-closure.
 */
class JvmCallbackIdBridgeTest {

    @Test
    fun plainClosuresAndAcClosuresDoNotShareCallbackIds() {
        val engine = LuaEngine().apply { openStandardLibs() }
        val oc = ObjectContainer()
        val closures = (0 until 100).map { i -> oc.makeClosure { listOf("c$i".lua) } }
        val acs = (0 until 100).map { i -> engine.createACClosure { listOf("a$i".lua) } }
        closures.forEachIndexed { i, c -> engine["c$i"] = c }
        acs.forEachIndexed { i, a -> engine["a$i"] = a }

        for (i in 0 until 100) {
            assertEquals(
                "c$i",
                engine.eval("return c$i()")[0].checkedString(),
                "plain closure c$i dispatched to the wrong Kotlin lambda",
            )
        }
        for (i in 0 until 100) {
            assertEquals(
                "a$i",
                engine.eval("return a$i()")[0].checkedString(),
                "AC closure a$i dispatched to the wrong Kotlin lambda",
            )
        }
    }
}
