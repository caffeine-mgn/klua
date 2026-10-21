package pw.binom.lua

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Регрессия на баг `FunctionRef.call` (найден 21.10.2026 в llm-proxy).
 *
 * Было: `ref.call(args)` терял ПЕРВЫЙ результат и оставлял его на Lua-стеке.
 * Причина — `topBefore` снимался ПОСЛЕ `push` функции, поэтому счёт
 * `getTop() - topBefore` съедал результат, а лишнее оставалось на стеке.
 * `return t` давал пустой список; три вызова подряд потом «вылезали» в
 * следующем вызове как [A, A, A, Z].
 *
 * После правки: результат ровно один, стек не растёт.
 */
class CommonFunctionRefCallTest : AbstractTest() {

    @Test
    fun callReturnsSingleResult() = start {
        val e = makeEngine()
        e.eval("function ret1(x) return x end")

        val ref = e["ret1"] as LuaValue.FunctionRef
        val out = ref.call("A".lua)
        assertEquals(1, out.size, "ровно один результат")
        assertEquals("A", out.single().toString())
    }

    @Test
    fun callReturnsMultipleResultsInOrder() = start {
        val e = makeEngine()
        e.eval("function ret3(x) return x, 'b', 'c' end")

        val ref = e["ret3"] as LuaValue.FunctionRef
        val out = ref.call("A".lua)
        assertEquals(listOf("A", "b", "c"), out.map { it.toString() }, "порядок и количество")
    }

    @Test
    fun callReturnsTable() = start {
        val e = makeEngine()
        e.eval("function rettable(t) t.k = 1; return t end")

        val ref = e["rettable"] as LuaValue.FunctionRef
        val arg = LuaValue.of(mapOf("k".lua to LuaValue.of(0L)))
        val out = ref.call(arg)
        assertEquals(1, out.size, "таблица возвращается одним результатом")
        val t = out.single() as? LuaValue.Table ?: throw AssertionError("ожидалась таблица")
        assertEquals(1L, (t.get("k".lua) as LuaValue.LuaInt).value, "правка на месте")
    }

    @Test
    fun callWithNoReturnGivesEmptyList() = start {
        val e = makeEngine()
        e.eval("function retnone(x) local y = x end")

        val ref = e["retnone"] as LuaValue.FunctionRef
        assertTrue(ref.call("A".lua).isEmpty(), "нет результата → пустой список")
    }

    @Test
    fun callWithExplicitNilGivesOneNil() = start {
        val e = makeEngine()
        e.eval("function retnil(x) return nil end")

        val ref = e["retnil"] as LuaValue.FunctionRef
        val out = ref.call("A".lua)
        assertEquals(1, out.size, "явный return nil — это один результат")
        assertTrue(out.single() is LuaValue.Nil)
    }

    /** Главная регрессия: стек не должен расти от повторных вызовов. */
    @Test
    fun repeatedCallsDoNotLeakOntoLuaStack() = start {
        val e = makeEngine()
        e.eval("function ret1(x) return x end")

        val ref = e["ret1"] as LuaValue.FunctionRef
        repeat(5) {
            val out = ref.call("A".lua)
            assertEquals(1, out.size, "вызов #$it должен возвращать ровно один результат")
        }

        // Если бы утечка осталась, следующий вызов подобрал бы накопленное.
        val out = ref.call("Z".lua)
        assertEquals(1, out.size, "после серии вызовов стек чист: $out")
        assertEquals("Z", out.single().toString())
    }

    /** ref.call и engine.call должны давать одинаковый результат. */
    @Test
    fun refCallMatchesEngineCall() = start {
        val e = makeEngine()
        e.eval("function ret2(x) return x, 'b' end")

        val ref = e["ret2"] as LuaValue.FunctionRef
        val viaRef = ref.call("A".lua).map { it.toString() }
        val viaEngine = e.call(ref, "A".lua).map { it.toString() }
        assertEquals(viaEngine, viaRef, "оба пути обязаны совпадать")
        assertEquals(listOf("A", "b"), viaRef)
    }

    @Test
    fun callWithArgumentsPassesThemAll() = start {
        val e = makeEngine()
        e.eval("function sum(a, b, c) return a + b + c end")

        val ref = e["sum"] as LuaValue.FunctionRef
        val out = ref.call(1.lua, 2.lua, 3.lua)
        assertEquals(1, out.size)
        assertEquals(6L, (out.single() as LuaValue.LuaInt).value)
    }

    @Test
    fun errorStillPropagates() = start {
        val e = makeEngine()
        e.eval("function boom() error('нарочно') end")

        val ref = e["boom"] as LuaValue.FunctionRef
        val thrown = try {
            ref.call()
            false
        } catch (t: LuaException) {
            assertTrue(t.message?.contains("нарочно") == true, "текст ошибки: ${t.message}")
            true
        }
        assertTrue(thrown, "ошибка должна доходить до Kotlin")
    }

    @Test
    fun engineRemainsUsableAfterError() = start {
        val e = makeEngine()
        e.eval(
            """
            function boom() error('нарочно') end
            function ok(x) return x end
            """.trimIndent(),
        )
        val boom = e["boom"] as LuaValue.FunctionRef
        try {
            boom.call()
        } catch (_: LuaException) {
        }
        val okRef = e["ok"] as LuaValue.FunctionRef
        val out = okRef.call("fine".lua)
        assertEquals(1, out.size, "движок рабочий после ошибки")
        assertEquals("fine", out.single().toString())
    }
}
