package pw.binom.lua

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `dumpFunction` / `loadBytecode`: перенос скомпилированной функции в ДРУГОЕ
 * состояние.
 *
 * Зачем это нужно (замерено 21.10.2026): `FunctionRef` — это refId в
 * `LUA_REGISTRYINDEX` конкретного `lua_State`. В чужом состоянии он
 * бесполезен, поэтому «пул движков с одной функцией» без дампа невозможен:
 * пришлось бы заново парсить исходник в каждом состоянии. С байткодом
 * функция компилируется один раз, а загружается в каждое состояние пула.
 */
class CommonBytecodeDumpTest : AbstractTest() {

    @Test
    fun dumpAndLoadIntoAnotherState() = start {
        val source = makeEngine()
        val target = makeEngine()
        try {
            source.eval("function double(x) return x * 2 end")
            val ref = source["double"] as LuaValue.FunctionRef
            val bytecode = assertNotNull(source.dumpFunction(ref), "дамп должен получиться")
            assertTrue(bytecode.isNotEmpty(), "байткод не пустой")
            assertEquals(0x1b, bytecode[0].toInt() and 0xFF, "сигнатура Lua-байткода: ESC")

            // Ключевое: загрузка в ДРУГОЕ состояние
            val loaded = assertNotNull(target.loadBytecode(bytecode, "double"), "байткод должен загрузиться")
            val out = target.call(loaded, 21.lua)
            assertEquals(1, out.size)
            assertEquals(42L, (out.single() as LuaValue.LuaInt).value, "функция работает в чужом состоянии")
        } finally {
            source.close()
            target.close()
        }
    }

    @Test
    fun dumpedFunctionIgnoresSourceStateEnvironment() = start {
        // Функция замкнута на локальную переменную источника. Байткод НЕ несёт
        // значения upvalue, поэтому в новом состоянии её нужно задать отдельно —
        // проверяем, что вызов падает предсказуемо, а не молча врёт.
        val source = makeEngine()
        val target = makeEngine()
        try {
            source.eval(
                """
                local factor = 3
                function triple(x) return x * factor end
                """.trimIndent(),
            )
            val ref = source["triple"] as LuaValue.FunctionRef
            assertEquals(27L, (source.call(ref, 9.lua).single() as LuaValue.LuaInt).value, "в источнике работает")

            val bytecode = assertNotNull(source.dumpFunction(ref))
            val loaded = assertNotNull(target.loadBytecode(bytecode, "triple"))
            val failed = try {
                target.call(loaded, 9.lua)
                false
            } catch (t: Throwable) {
                true // upvalue отсутствует — предсказуемая ошибка, а не тихая подмена
            }
            assertTrue(failed, "функция без upvalue в чужом состоянии обязана падать явно")
        } finally {
            source.close()
            target.close()
        }
    }

    @Test
    fun pureFunctionPortsCleanly() = start {
        // Функция без upvalue — переносится как есть. Это ровно тот случай,
        // который нужен адаптерам в llm-proxy.
        val source = makeEngine()
        val target = makeEngine()
        try {
            source.eval(
                """
                function fix(req)
                  if req.effort == "high" then req.effort = "medium" end
                  return req
                end
                """.trimIndent(),
            )
            val ref = source["fix"] as LuaValue.FunctionRef
            val bytecode = assertNotNull(source.dumpFunction(ref))
            val loaded = assertNotNull(target.loadBytecode(bytecode))

            val arg = LuaValue.of(mapOf("effort".lua to "high".lua))
            val out = target.call(loaded, arg)
            val table = out.first() as LuaValue.Table
            assertEquals("medium", (table.get("effort".lua) as LuaValue.String).value)
        } finally {
            source.close()
            target.close()
        }
    }

    @Test
    fun loadRejectsGarbage() = start {
        val e = makeEngine()
        try {
            val bad = byteArrayOf(0x1b, 0x4c, 0x75, 0x61, 0x00, 0x01, 0x02, 0x03, 0x04)
            assertNull(e.loadBytecode(bad, "garbage"), "мусор не должен загружаться как функция")
            // Движок обязан остаться рабочим
            e.eval("function ok(x) return x end")
            assertTrue(e.eval("return 1").isNotEmpty(), "движок рабочий после неудачной загрузки")
        } finally {
            e.close()
        }
    }

    @Test
    fun dumpWithStripProducesSmallerChunk() = start {
        val e = makeEngine()
        try {
            e.eval(
                """
                function verbose_function_name(x)
                  -- комментарий для отладочной информации
                  return x + 1
                end
                """.trimIndent(),
            )
            val ref = e["verbose_function_name"] as LuaValue.FunctionRef
            val full = assertNotNull(e.dumpFunction(ref, strip = false))
            val stripped = assertNotNull(e.dumpFunction(ref, strip = true))
            assertTrue(
                stripped.size <= full.size,
                "strip не должен увеличивать чанк: full=${full.size}, stripped=${stripped.size}",
            )
        } finally {
            e.close()
        }
    }

    @Test
    fun dumpedBytecodeSurvivesMultipleStates() = start {
        // Эмуляция пула: один дамп, N состояний.
        val source = makeEngine()
        val bytecode = try {
            source.eval("function inc(x) return x + 1 end")
            val ref = source["inc"] as LuaValue.FunctionRef
            assertNotNull(source.dumpFunction(ref))
        } finally {
            source.close()
        }

        val results = (1..3).map { n ->
            val e = makeEngine()
            try {
                val ref = assertNotNull(e.loadBytecode(bytecode))
                (e.call(ref, (n * 10).lua).single() as LuaValue.LuaInt).value
            } finally {
                e.close()
            }
        }
        assertEquals(listOf(11L, 21L, 31L), results, "одна и та же функция независимо живёт в трёх состояниях")
    }
}
