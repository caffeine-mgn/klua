package pw.binom.lua

fun LuaValue.stringOrNull() =
    (this as? LuaValue.String)?.value
fun LuaValue.intOrNull() =
    (this as? LuaValue.LuaInt)?.value
fun LuaValue.numberOrNull() =
    (this as? LuaValue.Number)?.value
fun LuaValue.booleanOrNull() =
    (this as? LuaValue.Boolean)?.value
fun LuaValue.tableOrNull() =
    this as? LuaValue.Table


fun LuaValue.checkedString() =
    (this as? LuaValue.String)?.value ?: throw LuaCastException("Can't cast ${this::class.simpleName} to String")

fun LuaValue.checkedInt() =
    (this as? LuaValue.LuaInt)?.value ?: throw LuaCastException("Can't cast ${this::class.simpleName} to LuaInt")

fun LuaValue.checkedNumber() =
    (this as? LuaValue.Number)?.value ?: throw LuaCastException("Can't cast ${this::class.simpleName} to Number")

fun LuaValue.checkedBoolean() =
    (this as? LuaValue.Boolean)?.value ?: throw LuaCastException("Can't cast ${this::class.simpleName} to Boolean")

fun LuaValue.checkedTable() =
    (this as? LuaValue.Table) ?: throw LuaCastException("Can't cast ${this::class.simpleName} to Table")

val LuaValue.isString get() = this is LuaValue.String
val LuaValue.isInt get() = this is LuaValue.LuaInt
val LuaValue.isNumber get() = this is LuaValue.Number
val LuaValue.isBoolean get() = this is LuaValue.Boolean
val LuaValue.isTable get() = this is LuaValue.Table
val LuaValue.isNil get() = this === LuaValue.Nil