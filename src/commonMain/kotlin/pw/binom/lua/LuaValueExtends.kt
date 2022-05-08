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

fun LuaValue.callableOrNull() =
    this as? LuaValue.Callable

fun LuaValue.userDataOrNull() =
    this as? LuaValue.UserData

fun LuaValue.lightUserDataOrNull() =
    this as? LuaValue.LightUserData

fun LuaValue.dataOrNull() =
    this as? LuaValue.Data

fun LuaValue.refOrNull() =
    this as? LuaValue.Ref

fun LuaValue.tableRefOrNull() =
    this as? LuaValue.TableRef

fun LuaValue.tableValueOrNull() =
    this as? LuaValue.TableValue

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

fun LuaValue.checkedTableRef() =
    (this as? LuaValue.TableRef) ?: throw LuaCastException("Can't cast ${this::class.simpleName} to TableRef")

fun LuaValue.checkedTableValue() =
    (this as? LuaValue.TableValue) ?: throw LuaCastException("Can't cast ${this::class.simpleName} to TableValue")

fun LuaValue.checkedRef() =
    (this as? LuaValue.Ref) ?: throw LuaCastException("Can't cast ${this::class.simpleName} to Ref")

fun LuaValue.checkedCallable() =
    (this as? LuaValue.Callable) ?: throw LuaCastException("Can't cast ${this::class.simpleName} to Callable")

fun LuaValue.checkedUserdata() =
    (this as? LuaValue.UserData) ?: throw LuaCastException("Can't cast ${this::class.simpleName} to UserData")

fun LuaValue.checkedLightUserdata() =
    (this as? LuaValue.LightUserData) ?: throw LuaCastException("Can't cast ${this::class.simpleName} to LightUserData")

fun LuaValue.checkedData() =
    (this as? LuaValue.Data) ?: throw LuaCastException("Can't cast ${this::class.simpleName} to Data")

fun LuaValue.checkedFunctionRef() =
    (this as? LuaValue.FunctionRef) ?: throw LuaCastException("Can't cast ${this::class.simpleName} to FunctionRef")

fun LuaValue.checkedFunctionValue() =
    (this as? LuaValue.FunctionValue) ?: throw LuaCastException("Can't cast ${this::class.simpleName} to FunctionValue")

val LuaValue.isString get() = this is LuaValue.String
val LuaValue.isInt get() = this is LuaValue.LuaInt
val LuaValue.isNumber get() = this is LuaValue.Number
val LuaValue.isBoolean get() = this is LuaValue.Boolean
val LuaValue.isTable get() = this is LuaValue.Table
val LuaValue.isUserdata get() = this is LuaValue.UserData
val LuaValue.isData get() = this is LuaValue.Data
val LuaValue.isLightUserdata get() = this is LuaValue.LightUserData
val LuaValue.isNil get() = this === LuaValue.Nil
val LuaValue.isRef get() = this is LuaValue.Ref
val LuaValue.isCallable get() = this is LuaValue.Callable

inline fun <reified T> LuaValue.Data.value(): T = value as T

val LuaValue.takeIfNotNil
    get() = if (isNil) null else this

val Long.lua
    get() = LuaValue.of(this)
val Double.lua
    get() = LuaValue.of(this)
val Float.lua
    get() = toDouble().lua
val Int.lua
    get() = toLong().lua
val Short.lua
    get() = toLong().lua
val Byte.lua
    get() = toLong().lua
val Boolean.lua
    get() = LuaValue.of(this)
val String.lua
    get() = LuaValue.of(this)
val Char.lua
    get() = toString().lua
