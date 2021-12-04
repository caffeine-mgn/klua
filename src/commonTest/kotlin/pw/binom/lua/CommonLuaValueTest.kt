package pw.binom.lua

import kotlin.test.Test
import kotlin.test.assertEquals

class CommonLuaValueTest {
    @Test
    fun readTable() {
        val e = LuaEngine()
        e["test"] = LuaValue.of(mapOf(1.0.lua to 2.0.lua))
        val table = e["test"]
        val m = table.checkedTable()
            .toMap()
            .entries
            .associate { it.key.checkedNumber() to it.value.checkedNumber() }
        assertEquals(1, m.size)
        assertEquals(2.0, m[1.0])
    }
}