package pw.binom.lua

actual sealed interface LuaValue {
    actual class FunctionValue(val callbackId: Int) : LuaValue {
        override fun toString(): kotlin.String = "function_value($callbackId)"
    }

    actual interface Data : LuaValue {
        actual val value: Any?
    }

    actual class UserData internal constructor(
        override val refId: Int,
        internal val ll: LuaContext,
    ) : RefObject, Data {

        val ptr: Long?
            get() {
                ll.push(this)
                val p = LuaNative.userdataPtr(ll.state, -1)
                LuaNative.pop(ll.state, 1)
                return if (p == 0L) null else p
            }

        actual override val value: Any?
            get() {
                val p = ptr ?: return null
                return StaticRefs.get(p)
            }

        actual override var metatable: LuaValue
            get() = getMetatable(ll, this)
            set(value) = setMetatable(ll, this, value)

        actual override fun call(vararg args: LuaValue): List<LuaValue> {
            ll.push(this)
            return pcallCall(ll, args.toList())
        }

        actual val toLightUserData: LightUserData get() = LightUserData(ptr)

        actual override fun callToString() = callToString(ll)
        override fun toString(): kotlin.String = "userdata(${ptr?.toString(16)})"

        fun dispose() {
            val p = ptr ?: return
            StaticRefs.dispose(p)
            LuaNative.unref(ll.state, LUA_REGISTRYINDEX, refId)
        }
    }

    actual class LightUserData(val ptr: Long?) : Data {
        actual constructor(value: Any?) : this(StaticRefs.intern(value))
        actual override val value: Any? get() = StaticRefs.get(ptr)
        fun dispose() { StaticRefs.dispose(ptr) }
        override fun toString(): kotlin.String = "lightuserdata(${ptr?.toString(16) ?: "0x0"})"
    }

    actual class Number actual constructor(actual val value: Double) : LuaValue {
        override fun toString(): kotlin.String = value.toString()
        override fun hashCode(): Int = value.hashCode()
        override fun equals(other: Any?): kotlin.Boolean {
            if (other == null || other !is Number) return false
            return value == other.value
        }
    }

    actual class LuaInt actual constructor(actual val value: Long) : LuaValue {
        override fun toString(): kotlin.String = value.toString()
        override fun hashCode(): Int = value.hashCode()
        override fun equals(other: Any?): kotlin.Boolean = other is LuaInt && value == other.value
    }

    actual class Boolean actual constructor(actual val value: kotlin.Boolean) : LuaValue {
        override fun toString(): kotlin.String = value.toString()
        override fun hashCode(): Int = value.hashCode()
        override fun equals(other: Any?): kotlin.Boolean = other is Boolean && value == other.value
    }

    actual class String actual constructor(actual val value: kotlin.String) : LuaValue {
        override fun toString(): kotlin.String = value
        override fun hashCode(): Int = value.hashCode()
        override fun equals(other: Any?): kotlin.Boolean = other is String && value == other.value
    }

    actual sealed interface Ref : LuaValue {
        val refId: Int
    }

    actual interface Table : LuaValue {
        actual val rawSize: Int
        actual val size: LuaValue
        actual fun toMap(): Map<LuaValue, LuaValue>
        actual fun rawGet(key: LuaValue): LuaValue
        actual fun rawSet(key: LuaValue, value: LuaValue)
        actual operator fun set(key: LuaValue, value: LuaValue)
        actual operator fun get(key: LuaValue): LuaValue
        actual fun toValue(): TableValue
        actual fun toList(): List<LuaValue>
    }

    actual interface Meta : LuaValue {
        actual var metatable: LuaValue
    }

    actual sealed interface RefObject : Ref, Callable, Meta {
        actual fun callToString(): kotlin.String
    }

    actual interface Callable : LuaValue {
        actual fun call(vararg args: LuaValue): List<LuaValue>
    }

    actual class TableRef internal constructor(
        override val refId: Int,
        val ptr: Long,
        internal val ll: LuaContext,
    ) : Table, RefObject {

        actual override operator fun get(key: LuaValue): LuaValue {
            ll.push(this)
            pushValue(ll.state, key)
            LuaNative.getTable(ll.state, -2)
            val v = ll.readValue(-1, true)
            LuaNative.pop(ll.state, 2)
            return v
        }

        actual override operator fun set(key: LuaValue, value: LuaValue) {
            ll.push(this)
            pushValue(ll.state, key)
            pushValue(ll.state, value)
            LuaNative.setTable(ll.state, -3)
            LuaNative.pop(ll.state, 1)
        }

        actual override fun rawGet(key: LuaValue): LuaValue {
            ll.push(this)
            pushValue(ll.state, key)
            LuaNative.rawGet(ll.state, -2)
            val v = ll.readValue(-1, true)
            LuaNative.pop(ll.state, 1)
            return v
        }

        actual override fun rawSet(key: LuaValue, value: LuaValue) {
            ll.push(this)
            pushValue(ll.state, key)
            pushValue(ll.state, value)
            LuaNative.rawSet(ll.state, -3)
            LuaNative.pop(ll.state, 1)
        }

        actual override val size: LuaValue
            get() {
                ll.push(this)
                LuaNative.len(ll.state, -1)
                val v = ll.readValue(-1, true)
                LuaNative.pop(ll.state, 1)
                return v
            }

        actual override val rawSize: Int
            get() {
                ll.push(this)
                val n = LuaNative.rawLen(ll.state, -1).toInt()
                LuaNative.pop(ll.state, 1)
                return n
            }

        actual override fun toMap(): Map<LuaValue, LuaValue> = toValue().toMap()

        actual override fun call(vararg args: LuaValue): List<LuaValue> {
            ll.push(this)
            return pcallCall(ll, args.toList())
        }

        actual override var metatable: LuaValue
            get() = getMetatable(ll, this)
            set(value) = setMetatable(ll, this, value)

        actual override fun callToString() = callToString(ll)

        actual override fun toValue(): TableValue {
            ll.push(this)
            val r = ll.readValue(-1, false) as TableValue
            LuaNative.pop(ll.state, 1)
            return r
        }

        actual override fun toList(): List<LuaValue> = (1..rawSize).map { get(of(it.toLong())) }

        override fun equals(other: Any?): kotlin.Boolean = other is TableRef && ptr == other.ptr
        override fun hashCode(): Int = ptr.hashCode()
        override fun toString(): kotlin.String = "table(${ptr.toString(16)})"
    }

    actual class FunctionRef internal constructor(
        override val refId: Int,
        val ptr: Long,
        internal val ll: LuaContext,
    ) : Ref, Callable {
        override fun toString(): kotlin.String = "function(${ptr.toString(16)})"

        actual override fun call(vararg args: LuaValue): List<LuaValue> {
            ll.push(this)
            return pcallCall(ll, args.toList())
        }

        actual fun toValue(): FunctionValue {
            ll.push(this)
            val r = ll.readValue(-1, false) as FunctionValue
            LuaNative.pop(ll.state, 1)
            return r
        }

        override fun equals(other: Any?): kotlin.Boolean = other is FunctionRef && refId == other.refId
        override fun hashCode(): Int = refId
    }

    actual class TableValue constructor(
        val map: HashMap<LuaValue, LuaValue>,
        actual override var metatable: LuaValue,
    ) : LuaValue, Table, Meta {
        actual constructor(map: Map<LuaValue, LuaValue>) : this(HashMap(map), Nil)
        actual constructor(vararg keys: Pair<LuaValue, LuaValue>) : this(keys.toMap())
        actual constructor() : this(HashMap(), Nil)

        override fun toString(): kotlin.String =
            if (metatable == Nil) "table_value($map)" else "table_value($map, metatable: $metatable)"

        actual override val rawSize: Int get() = map.size
        actual override val size: LuaValue get() = LuaInt(rawSize.toLong())

        actual override fun rawGet(key: LuaValue): LuaValue = map[key] ?: Nil
        actual override fun rawSet(key: LuaValue, value: LuaValue) {
            if (value is Nil) map.remove(key) else map[key] = value
        }

        actual override fun set(key: LuaValue, value: LuaValue) { rawSet(key, value) }
        actual override fun get(key: LuaValue): LuaValue = rawGet(key)
        actual override fun toValue(): TableValue = this

        actual override fun toList(): List<LuaValue> = (1..rawSize).map { map[of(it.toLong())] ?: Nil }
        actual override fun toMap(): Map<LuaValue, LuaValue> = map
    }

    actual object Nil : LuaValue {
        override fun toString(): kotlin.String = "nil"
    }

    actual companion object {
        actual fun of(value: Double): Number = Number(value)
        actual fun of(value: Long): LuaInt = LuaInt(value)
        actual fun of(value: kotlin.Boolean): Boolean = Boolean(value)
        actual fun of(value: kotlin.String): String = String(value)
        actual fun of(table: Map<LuaValue, LuaValue>): TableValue = TableValue(table)
        actual fun of(table: Map<LuaValue, LuaValue>, metatable: LuaValue): TableValue =
            TableValue(HashMap(table), metatable)
        actual fun of(table: List<LuaValue>): TableValue {
            val m = HashMap<LuaValue, LuaValue>()
            table.forEachIndexed { i, v -> m[of(i.toLong() + 1)] = v }
            return TableValue(m)
        }
        actual fun of(table: Array<LuaValue>): TableValue {
            val m = HashMap<LuaValue, LuaValue>()
            table.forEachIndexed { i, v -> m[of(i.toLong() + 1)] = v }
            return TableValue(m)
        }
        actual fun of(table: List<LuaValue>, metatable: LuaValue): TableValue {
            val m = HashMap<LuaValue, LuaValue>()
            table.forEachIndexed { i, v -> m[of(i.toLong() + 1)] = v }
            return TableValue(m, metatable)
        }
    }
}

internal fun getMetatable(ll: LuaContext, value: LuaValue.Meta): LuaValue {
    ll.push(value)
    return if (LuaNative.getMetatable(ll.state, -1) != 0) {
        val s = ll.readValue(-1, true)
        LuaNative.pop(ll.state, 2)
        s
    } else {
        LuaNative.pop(ll.state, 1)
        LuaValue.Nil
    }
}

internal fun setMetatable(ll: LuaContext, value: LuaValue.Meta, table: LuaValue) {
    ll.push(value)
    pushValue(ll.state, table)
    LuaNative.setMetatable(ll.state, -2)
    LuaNative.pop(ll.state, 1)
}

internal fun LuaValue.RefObject.callToString(ll: LuaContext): kotlin.String {
    ll.push(this)
    val s = LuaNative.toLString(ll.state, -1)
    LuaNative.pop(ll.state, 1)
    return s ?: ""
}

internal fun LuaContext.push(value: LuaValue) {
    pushValue(state, value)
}

internal fun pcallCall(ll: LuaContext, args: List<LuaValue>): List<LuaValue> {
    val topBefore = LuaNative.getTop(ll.state)
    args.forEach { pushValue(ll.state, it) }
    val r = LuaNative.pcall(ll.state, args.size, -1, 0)
    when (r) {
        0 -> {
            val count = LuaNative.getTop(ll.state) - topBefore + 1
            val list = (1..count).map { ll.readValue(it, true) }
            LuaNative.pop(ll.state, count)
            return list
        }
        4 -> {
            val msg = LuaNative.toString(ll.state, -1) ?: "runtime error"
            LuaNative.pop(ll.state, 1)
            throw LuaException(msg)
        }
        5 -> throw RuntimeException("memory allocation error")
        6 -> throw RuntimeException("error while running the message handler")
        else -> throw RuntimeException("Unknown pcall status: $r")
    }
}
