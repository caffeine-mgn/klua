package pw.binom.lua

/**
 * Lua 5.4 status codes returned by [lua_pcall] and other runtime entry
 * points. The values are stable across all Lua 5.4 builds (defined in
 * lua.h as the LUA_OK/LUA_ERR* family).
 *
 * Centralising the constants here lets JVM (where raw integer literals
 * historically appeared as `4/5/6`) and POSIX (which has cinterop
 * bindings to the same names) agree on the exact codes used in
 * dispatch tables. Two of the most consequential bugs in the recent
 * history (E3 / R5 / L5 — `pcallCall` mis-mapping Lua pcall status)
 * trace directly to JVM-side magic-number dispatch tables that drifted
 * out of sync with the actual Lua API.
 */
object LuaStatus {
    /** lua.h: LUA_OK */
    const val OK: Int = 0
    /** lua.h: LUA_ERRRUN — runtime error (the common case: `error("boom")`) */
    const val ERRRUN: Int = 2
    /** lua.h: LUA_ERRSYNTAX — syntax error during compile */
    const val ERRSYNTAX: Int = 3
    /** lua.h: LUA_ERRMEM — memory allocation failure */
    const val ERRMEM: Int = 4
    /** lua.h: LUA_ERRERR — error while running the error handler */
    const val ERRERR: Int = 5
}

/**
 * Lua 5.4 type tags (lua.h LUA_T* family). Used by [LuaContext.readValueAt]
 * on JVM and [LuaContext.readValue] on POSIX to dispatch the type of a
 * stack value into the corresponding [LuaValue] subclass.
 */
object LuaType {
    const val NONE: Int = -1
    const val NIL: Int = 0
    const val BOOLEAN: Int = 1
    const val LIGHTUSERDATA: Int = 2
    const val NUMBER: Int = 3
    const val STRING: Int = 4
    const val TABLE: Int = 5
    const val FUNCTION: Int = 6
    const val USERDATA: Int = 7
    const val THREAD: Int = 8
}
