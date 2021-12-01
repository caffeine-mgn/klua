package pw.binom.lua

@Deprecated(message = "Debug Tool", level = DeprecationLevel.WARNING)
actual fun LuaEngine.printStack(message: String?) {
    val msg = message ?: "Lua Stack"
    println("---===$msg===---")
    println("JVM KLua implementation doesn't provide any Lua Stack")
    println("---===$msg===---")
}
