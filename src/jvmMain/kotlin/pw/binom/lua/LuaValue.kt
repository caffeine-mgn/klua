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

        override fun toString(): kotlin.String = "FunctionRef(${value.hashCode().toString(16)})"
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
        override fun toString(): kotlin.String = value.toString()
        override fun hashCode(): Int = value.hashCode()
        override fun equals(other: Any?): kotlin.Boolean = value == other
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

    actual class TableRef(override val native: org.luaj.vm2.LuaTable) : Ref, Table, Callable, Meta {
        actual fun toValue(): TableValue =
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

        override var metatable: LuaValue
            get() = of(native.getmetatable() ?: LuaJValue.NIL, ref = true)
            set(value) {
                native.setmetatable(value.makeNative())
            }

        override fun call(vararg args: LuaValue): List<LuaValue> =
            try {
                native.invoke(args.toNative()).toCommon()
            } catch (e: LuaError) {
                throw LuaException(e.message)
            }

        override fun makeNative(): LuaJValue = native
        override val size
            get() = of(native.len(), ref = true)

        override fun toString(): kotlin.String = "TableRef(${native.hashCode().toString(16)})"
    }

    actual class TableValue(val map: HashMap<LuaValue, LuaValue>, override var metatable: LuaValue) : LuaValue,
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

        override fun makeNative(): org.luaj.vm2.LuaValue =
            map.toNative()

        actual constructor(map: Map<LuaValue, LuaValue>) : this(HashMap(map), Nil)
        actual constructor(vararg keys: Pair<LuaValue, LuaValue>) : this(keys.toMap())
        actual constructor() : this(emptyMap())

        override fun toString(): kotlin.String {
            return toMap().toString()
        }

        override val rawSize: Int
            get() = map.size
        override val size: LuaValue
            get() = LuaInt(rawSize.toLong())

        override fun toMap(): Map<LuaValue, LuaValue> =
            map
    }

    actual class UserData(val native: LuaUserdata) : LuaValue {
        override fun makeNative(): org.luaj.vm2.LuaValue = native
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
            println()
            return when (value.type()) {
                LuaJValue.TNUMBER -> Number(value.checkdouble())
                LuaJValue.TINT -> LuaInt(value.checkint().toLong())
                LuaJValue.TBOOLEAN -> Boolean(value.checkboolean())
                LuaJValue.TSTRING -> String(value.checkjstring())
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
    val t = LuaTable(0, size)
    forEach {
        t.rawset(it.key.makeNative(), it.value.makeNative())
    }
    return t
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