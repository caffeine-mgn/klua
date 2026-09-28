package pw.binom.lua

/**
 * One of the standard Lua 5.4 libraries that can be loaded into a [LuaEngine].
 *
 * A fresh engine is bare: no standard library is loaded unless it is
 * explicitly requested — either through the [LuaEngine] constructor or via
 * [LuaEngine.openLibs]. This is the safe default for running untrusted Lua
 * source; pass [LuaLibrary.ALL] (or call [LuaEngine.openStandardLibs]) to
 * restore the full historical `luaL_openlibs` behaviour.
 *
 * The declaration order mirrors Lua's `linit.c` `loadedlibs` table (and the
 * `KLUA_LIBS` table in `klua_jni.c`): the enum [ordinal] is the bit index in
 * the native library mask. Do not reorder.
 */
enum class LuaLibrary {
    BASE,
    PACKAGE,
    COROUTINE,
    TABLE,
    IO,
    OS,
    STRING,
    MATH,
    UTF8,
    DEBUG,
    ;

    /** Bit used by [LuaEngine.openLibs]'s native mask. */
    internal val bit: Int get() = 1 shl ordinal

    /** Lua module name as passed to `luaL_requiref`. */
    internal val luaName: String
        get() = when (this) {
            BASE -> "_G"
            PACKAGE -> "package"
            COROUTINE -> "coroutine"
            TABLE -> "table"
            IO -> "io"
            OS -> "os"
            STRING -> "string"
            MATH -> "math"
            UTF8 -> "utf8"
            DEBUG -> "debug"
        }

    companion object {
        /** Every standard library — the historical full-standard-library behaviour. */
        val ALL: Set<LuaLibrary> = entries.toSet()

        /** Libraries considered safe for untrusted scripts: no `io`, `os`, `package`, `debug`. */
        val SAFE: Set<LuaLibrary> = setOf(BASE, COROUTINE, TABLE, STRING, MATH, UTF8)
    }
}
