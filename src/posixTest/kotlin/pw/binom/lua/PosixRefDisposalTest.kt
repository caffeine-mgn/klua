@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
    kotlin.native.runtime.NativeRuntimeApi::class,
)

package pw.binom.lua

import kotlin.native.runtime.GC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Регрессия на SIGSEGV из финализатора (klua <= 0.3.0).
 *
 * `LuaValue`-обёртки (`TableRef`, `FunctionRef`, `UserData`) несут
 * `createCleaner`, который раньше вызывал `luaL_unref` прямо на нити GC.
 * После `LuaEngine.close()` это был use-after-free: процесс падал с SIGSEGV
 * примерно через 10 секунд после старта, как только отрабатывал первый цикл
 * сборки мусора.
 *
 * Теперь финализатор только складывает id в очередь, а `luaL_unref`
 * выполняется на «хозяйской» нити. Тест роняет процесс (а не ассерт), если
 * поведение сломается, поэтому его достаточно запустить — «прошёл» значит
 * «не упали».
 */
class PosixRefDisposalTest {

    @Test
    fun refsSurviveEngineCloseAndGc() {
        repeat(20) {
            val engine = LuaEngine()
            val values = engine.eval(
                """
                local t = { a = 1, b = "two" }
                return t, function() return t end
                """.trimIndent(),
            )
            assertTrue(values.isNotEmpty())
            // Обёртки с ref'ами: их финализаторы обязаны пережить close().
            val table = values[0].tableOrNull()
            assertEquals(1L, (table?.rawGet("a".lua) as? LuaValue.LuaInt)?.value)
            assertEquals("two", (table?.rawGet("b".lua) as? LuaValue.String)?.value)
            assertEquals(2, values.size)
            assertTrue(values[1] is LuaValue.FunctionRef, "функция должна прийти как ref-обёртка")

            engine.close()
            // Заставляем GC отработать финализаторы, пока указатели уже мертвы.
            GC.collect()
        }
        // Ещё один цикл уже после всех close().
        repeat(3) { GC.collect() }
    }

    @Test
    fun refsAreRecycledWhileEngineIsAlive() {
        val engine = LuaEngine()
        // Каждый вызов создаёт TableRef/String-обёртки; их ref'ы должны
        // освобождаться, иначе реестр Lua разрастается на каждом запросе.
        repeat(200) {
            val values = engine.eval("return { x = 1 }, 'payload'")
            assertEquals(1L, ((values[0].tableOrNull())?.rawGet("x".lua) as? LuaValue.LuaInt)?.value)
            assertEquals("payload", (values[1] as? LuaValue.String)?.value)
            if (it % 20 == 0) {
                GC.collect()
            }
        }
        GC.collect()
        // Дренируем накопленное и убеждаемся, что состояние после этого живое.
        engine.eval("return 1")
        engine.close()
    }
}
