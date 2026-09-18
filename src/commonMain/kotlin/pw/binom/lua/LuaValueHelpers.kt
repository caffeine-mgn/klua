package pw.binom.lua

/**
 * Pure-common helpers for [LuaValue] construction. Lives in its own file
 * so the JVM bytecode class name (`LuaValueHelpersKt`) doesn't clash with
 * the platform-specific `LuaValue.kt` files in jvmMain/posixMain that
 * both compile down to `LuaValueKt`.
 */

/**
 * Builds a [LuaValue.TableValue] from string-keyed entries, applying the
 * standard `.lua` conversion so callers can pass raw strings instead of
 * `"key".lua`. Lets metatable construction read like the Lua source it
 * mirrors:
 *
 *     metatable = luaTableOf("__gc" to gcFn)
 *
 * instead of
 *
 *     metatable = LuaValue.TableValue("__gc".lua to gcFn)
 */
fun luaTableOf(vararg pairs: Pair<String, LuaValue>): LuaValue.TableValue =
    LuaValue.TableValue(pairs.associate { it.first.lua to it.second })

/**
 * Builds a 1-indexed [LuaValue.TableValue] from any [Iterable] of values.
 * Used by the [LuaValue.of] overloads for List/Array; extracted here to
 * deduplicate six near-identical forEachIndexed loops (3 per target) and
 * give the conversion of `0..size-1 -> 1..size` a single source of truth.
 */
internal fun sequenceToTable(values: Iterable<LuaValue>, metatable: LuaValue? = null): LuaValue.TableValue {
    val map = HashMap<LuaValue, LuaValue>(values.count().let { (it * 1.4).toInt() + 1 })
    var i = 1L
    for (v in values) {
        map[LuaValue.LuaInt(i)] = v
        i++
    }
    // Build the vararg pair array explicitly so compiler picks the
    // Pair<LuaValue, LuaValue> overload unambiguously.
    val pairs = map.entries.map { (k, v) -> k to v }.toTypedArray()
    return LuaValue.TableValue(*pairs)
}
