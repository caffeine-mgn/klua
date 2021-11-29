package pw.binom.lua

/**
 * Prints Lua stack as is. Don't change it
 * @param message message for print
 */
@Deprecated(message = "Debug Tool", level = DeprecationLevel.WARNING)
internal expect fun LuaEngine.printStack(message: String? = null)