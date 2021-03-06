package pw.binom.lua

open class LuaCastException : LuaException {
    constructor() : super()
    constructor(message: String?) : super(message)
    constructor(message: String?, cause: Throwable?) : super(message, cause)
    constructor(cause: Throwable?) : super(cause)

    override fun toString(): String = message ?: "Error"
}
