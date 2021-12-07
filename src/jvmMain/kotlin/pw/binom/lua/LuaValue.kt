package pw.binom.lua

import org.luaj.vm2.*
import org.luaj.vm2.LuaFunction
import java.lang.IllegalArgumentException
import org.luaj.vm2.LuaValue as LuaJValue

actual sealed interface LuaValue {

    fun makeNative(): LuaJValue
//    val native: LuaJValue

    actual class FunctionValue(val value: LuaFunction) : LuaValue {
        override fun makeNative(): LuaJValue = value
        override fun toString(): kotlin.String = "function_value(${value.hashCode().toUInt().toString(16)})"
    }

    actual class FunctionRef(val value: LuaFunction) : Ref, Callable {
        actual override fun call(vararg args: LuaValue): List<LuaValue> =
            try {
                value.invoke(args.toNative()).toCommon()
            } catch (e: LuaError) {
                throw LuaException(e.message)
            }

        override fun makeNative(): org.luaj.vm2.LuaValue = value

        override val native: org.luaj.vm2.LuaValue
            get() = value

        override fun toString(): kotlin.String = "function(${value.hashCode().toUInt().toString(16)})"
        actual fun toValue(): FunctionValue = FunctionValue(value)
        override fun equals(other: Any?): kotlin.Boolean {
            if (other !is FunctionRef) {
                return false
            }
            return other.value === value
        }

        override fun hashCode(): Int = value.hashCode()
    }

    actual class Number actual constructor(actual val value: Double) : LuaValue {
        override fun toString(): kotlin.String = value.toString()
        override fun hashCode(): Int = value.hashCode()
        override fun makeNative(): LuaJValue =
            LuaJValue.valueOf(value)

        override fun equals(other: Any?): kotlin.Boolean = value == other
    }

    actual class LuaInt actual constructor(actual val value: Long) : LuaValue {
        override fun toString(): kotlin.String = value.toString()
        override fun hashCode(): Int = value.hashCode()
        override fun equals(other: Any?): kotlin.Boolean = value == other
        override fun makeNative(): LuaJValue =
            LuaJValue.valueOf(value.toInt())
    }

    actual class Boolean actual constructor(actual val value: kotlin.Boolean) : LuaValue {
        override fun toString(): kotlin.String = value.toString()
        override fun hashCode(): Int = value.hashCode()
        override fun equals(other: Any?): kotlin.Boolean = value == other
        override fun makeNative(): LuaJValue =
            LuaJValue.valueOf(value)
    }

    actual class String actual constructor(actual val value: kotlin.String) : LuaValue {
        override fun toString(): kotlin.String = value
        override fun hashCode(): Int = value.hashCode()
        override fun equals(other: Any?): kotlin.Boolean = other is String && value == other.value
        override fun makeNative(): LuaJValue =
            LuaJValue.valueOf(value)
    }

    actual object Nil : LuaValue {
        override fun toString(): kotlin.String = "nil"
        override fun makeNative(): LuaJValue =
            LuaJValue.NIL
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
    }

    actual sealed interface Ref : LuaValue {
        val native: org.luaj.vm2.LuaValue
    }

    actual interface Callable : LuaValue {
        actual fun call(vararg args: LuaValue): List<LuaValue>
    }

    actual interface Meta : LuaValue {
        actual var metatable: LuaValue
    }

    actual sealed interface RefObject : Ref, Callable, Meta {
        actual fun callToString(): kotlin.String
    }

    actual class TableRef(override val native: LuaTable) : Table, RefObject {
        override fun toValue(): TableValue =
            TableValue(native.toMap(), metatable)

        override val rawSize: Int
            get() = native.rawlen()

        override fun toMap(): Map<LuaValue, LuaValue> = native.toMap()
        override fun rawGet(key: LuaValue): LuaValue = of(native.rawget(key.makeNative()), ref = true)

        override fun rawSet(key: LuaValue, value: LuaValue) {
            native.rawset(key.makeNative(), value.makeNative())
        }

        override fun set(key: LuaValue, value: LuaValue) {
            native.set(key.makeNative(), value.makeNative())
        }

        override fun get(key: LuaValue): LuaValue =
            of(native.get(key.makeNative()), ref = true)

        actual override var metatable: LuaValue
            get() = of(native.getmetatable() ?: LuaJValue.NIL, ref = true)
            set(value) {
                native.setmetatable(value.makeNative())
            }

        actual override fun call(vararg args: LuaValue): List<LuaValue> =
            try {
                native.invoke(args.toNative()).toCommon()
            } catch (e: LuaError) {
                throw LuaException(e.message)
            }

        override fun callToString(): kotlin.String =
            native.tostring().checkjstring()

        override fun makeNative(): LuaJValue = native
        override val size
            get() = of(native.len(), ref = true)

        override fun toString(): kotlin.String = "table(${native.hashCode().toUInt().toString(16)})"
    }

    actual class TableValue(val map: HashMap<LuaValue, LuaValue>, actual override var metatable: LuaValue) : LuaValue,
        Table, Meta {
        override fun rawGet(key: LuaValue): LuaValue = map[key] ?: Nil
        override fun rawSet(key: LuaValue, value: LuaValue) {
            if (value is Nil) {
                map.remove(key)
            } else {
                map[key] = value
            }
        }

        override fun set(key: LuaValue, value: LuaValue) {
            rawSet(key, value)
        }

        override fun get(key: LuaValue): LuaValue =
            rawGet(key)

        override fun toValue(): TableValue = this

        override fun makeNative(): org.luaj.vm2.LuaValue {
            val t = map.toNative()
            t.setmetatable(metatable.makeNative())
            return t
        }

        actual constructor(map: Map<LuaValue, LuaValue>) : this(HashMap(map), Nil)
        actual constructor(vararg keys: Pair<LuaValue, LuaValue>) : this(keys.toMap())
        actual constructor() : this(emptyMap())

        override fun toString(): kotlin.String =
            if (metatable == Nil) {
                "table_value(${toMap()})"
            } else {
                "table_value(${toMap()}, metatable: $metatable)"
            }

        override val rawSize: Int
            get() = map.size
        override val size: LuaValue
            get() = LuaInt(rawSize.toLong())

        override fun toMap(): Map<LuaValue, LuaValue> =
            map
    }

    actual interface Data : LuaValue {
        actual val value: Any?
    }

    actual class UserData(override val native: LuaUserdata) : RefObject, Data {
        override fun call(vararg args: LuaValue): List<LuaValue> =
            try {
                native.invoke(args.toNative()).toCommon()
            } catch (e: LuaError) {
                throw LuaException(e.message)
            }

        actual override var metatable: LuaValue
            get() = of(native.getmetatable() ?: LuaJValue.NIL, ref = true)
            set(value) {
                native.setmetatable(value.makeNative())
            }
        override val value: Any?
            get() = native.m_instance

        override fun makeNative(): org.luaj.vm2.LuaValue = native
        actual val toLightUserData: LightUserData
            get() = LightUserData(value)

        override fun toString(): kotlin.String = "userdata(${native.hashCode().toUInt().toString(16)})"
        override fun callToString(): kotlin.String = native.tostring().checkjstring()
    }

    actual class LightUserData actual constructor(actual override val value: Any?) : Data {
        override fun makeNative(): org.luaj.vm2.LuaValue =
            LuaJLightUserdata(value)

        override fun toString(): kotlin.String = "lightuserdata(${value?.hashCode()?.toUInt()?.toString(16) ?: 0})"
    }

    actual companion object {
        actual fun of(value: Double): Number = Number(value)
        actual fun of(value: Long): LuaInt = LuaInt(value)
        actual fun of(value: kotlin.Boolean): Boolean = Boolean(value)
        actual fun of(value: kotlin.String): String = String(value)
        actual fun of(
            table: Map<LuaValue, LuaValue>,
            metatable: LuaValue
        ): TableValue =
            TableValue(HashMap(table), metatable)

        fun of(value: LuaJValue, ref: kotlin.Boolean): LuaValue {
            return when (value.type()) {
                LuaJValue.TNUMBER -> Number(value.checkdouble())
                LuaJValue.TINT -> LuaInt(value.checkint().toLong())
                LuaJValue.TBOOLEAN -> Boolean(value.checkboolean())
                LuaJValue.TSTRING -> String(value.checkjstring())
                LuaJLightUserdata.TYPE -> LightUserData(value.checkuserdata())
                LuaJValue.TUSERDATA -> UserData(value as LuaUserdata)
                LuaJValue.TTABLE -> {
                    if (ref) {
                        TableRef(value.checktable())
                    } else {
                        val v = TableValue(value.checktable().toMap())
                        v.metatable = of(value.getmetatable(), ref = true)
                        v
                    }
                }
                LuaJValue.TFUNCTION -> {
                    if (ref) {
                        FunctionRef(value.checkfunction())
                    } else {
                        FunctionValue(value.checkfunction())
                    }
                }
                LuaJValue.TNIL -> Nil
                else -> throw IllegalArgumentException("Unknown type ${value.typename()}")
            }
        }

        actual fun of(table: Map<LuaValue, LuaValue>): TableValue = TableValue(table)
    }
}

internal fun Map<LuaValue, LuaValue>.toNative(): LuaTable {
    val named = entries.flatMap { listOf(it.key.makeNative(), it.value.makeNative()) }.toTypedArray()
    return KLuaTable(named, emptyArray())
//    forEach {
//        t.rawset(it.key.makeNative(), it.value.makeNative())
//    }
//    return t
}

internal fun LuaTable.toMap(): HashMap<LuaValue, LuaValue> {
    var key: LuaJValue = LuaJValue.NIL
    val map = HashMap<LuaValue, LuaValue>()
    do {
        val next = next(key)
        if (next == LuaJValue.NIL) {
            break
        }
        key = next.arg(1)
        map[LuaValue.of(next.arg(1), ref = true)] = LuaValue.of(next.arg(2), ref = true)
    } while (true)
    return map
}