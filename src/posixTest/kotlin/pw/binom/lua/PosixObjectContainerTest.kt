@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pw.binom.lua

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * POSIX-специфичное покрытие [ObjectContainer]. Путь `removeClosure`
 * раньше дважды освобождал upvalue-StableRef (AUDIT-2026-10-12.md #3) —
 * дефект латентный (K/N переносит повторный `StableRef.dispose()`), поэтому
 * тест проверяет наблюдаемый контракт, а не само освобождение.
 */
class PosixObjectContainerTest {

    @Test
    fun removeClosureReturnsTrueAndClearsClosure() {
        val oc = ObjectContainer()
        val func = LuaFunction { emptyList() }
        val closure = oc.makeClosure(func)
        assertTrue(oc.removeClosure(closure), "removeClosure of a live closure must return true")
        assertNull(oc.getClosure(closure), "closure must be gone after removeClosure")
    }

    @Test
    fun removeClosureOfForeignClosureIsFalse() {
        val oc = ObjectContainer()
        // A FunctionValue that was never registered through makeClosure has no
        // single upvalue, so removeClosure must report "not ours" rather than
        // touch a StableRef it does not own.
        val foreign = LuaValue.FunctionValue(ptr = CLOSURE_FUNCTION, upValues = emptyList())
        assertFalse(oc.removeClosure(foreign))
    }
}
