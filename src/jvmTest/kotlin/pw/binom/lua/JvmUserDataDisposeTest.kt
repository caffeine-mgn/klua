package pw.binom.lua

import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * Регресс на `AUDIT-2026-10-12.md` #2: [LuaValue.UserData.dispose] должен
 * освобождать registry-слот ровно один раз. До фикса `dispose()` запускал
 * cleaner-действие (deferUnref) И вызывал `luaL_unref` напрямую, ломая freelist
 * реестра Lua — два последовательных `luaL_ref` возвращали один и тот же слот.
 */
class JvmUserDataDisposeTest {

    @Test
    fun explicitUserDataDisposeDoesNotDoubleUnrefRegistry() {
        val engine = LuaEngine().apply { openStandardLibs() }
        engine.createAC("payload").dispose()
        val r1 = engine.eval("return function() end")[0] as LuaValue.FunctionRef
        val r2 = engine.eval("return function() end")[0] as LuaValue.FunctionRef
        assertNotEquals(
            r1.refId,
            r2.refId,
            "registry freelist returned the same slot twice after UserData.dispose()",
        )
    }
}
