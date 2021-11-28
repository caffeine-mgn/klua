package pw.binom.lua

import org.luaj.vm2.*
import org.luaj.vm2.LuaFunction
import java.lang.IllegalArgumentException
import org.luaj.vm2.LuaValue as LuaJValue

actual sealed interface LuaValue {
    val native: LuaJValue

    actual class Function(val value: LuaFunction) : LuaValue {
        override val native: org.luaj.vm2.LuaValue
            get() = value
    }

    actual class Number constructor(override val native: org.luaj.vm2.LuaNumber) : LuaValue {
        actual constructor(value: Double) : this(LuaJValue.valueOf(value))

        override fun toString(): kotlin.String = value.toString()
        override fun hashCode(): Int = value.hashCode()
        override fun equals(other: Any?): kotlin.Boolean = value == other
        actual val value: Double
            get() = native.checkdouble()
    }

    actual class LuaInt constructor(override val native: org.luaj.vm2.LuaNumber) : LuaValue {
        actual constructor(value: Long) : this(LuaInteger.valueOf(value))

        override fun toString(): kotlin.String = value.toString()
        override fun hashCode(): Int = value.hashCode()
        override fun equals(other: Any?): kotlin.Boolean = value == other
        actual val value: Long
            get() = native.checklong()
    }

    actual class Boolean constructor(override val native: org.luaj.vm2.LuaBoolean) : LuaValue {
        actual constructor(value: kotlin.Boolean) : this(LuaJValue.valueOf(value))

        override fun toString(): kotlin.String = value.toString()
        override fun hashCode(): Int = value.hashCode()
        override fun equals(other: Any?): kotlin.Boolean = value == other
        actual val value: kotlin.Boolean
            get() = native.checkboolean()
    }

    actual class String constructor(override val native: org.luaj.vm2.LuaString) : LuaValue {
        actual constructor(value: kotlin.String) : this(LuaJValue.valueOf(value))

        override fun toString(): kotlin.String = value.toString()
        override fun hashCode(): Int = value.hashCode()
        override fun equals(other: Any?): kotlin.Boolean = value == other
        actual val value: kotlin.String
            get() = native.checkjstring()
    }

    actual object Nil : LuaValue {
        override val native: org.luaj.vm2.LuaValue
            get() = LuaJValue.NIL

        override fun toString(): kotlin.String = "nil"
    }

    actual class Table(override val native: LuaTable) : LuaValue {
        actual operator fun get(key: LuaValue): LuaValue = of(native.get(key.native))
        actual operator fun set(key: LuaValue, value: LuaValue) {
            native.rawset(key.native, value.native)
        }

        actual constructor(map: Map<LuaValue, LuaValue>) : this(buildTableFromMap(map))
        actual constructor(vararg keys: Pair<LuaValue, LuaValue>) : this(keys.toMap())
        actual constructor() : this(emptyMap())

        override fun toString(): kotlin.String {
            var key: LuaJValue = LuaJValue.NIL
            val map = HashMap<LuaValue, LuaValue>()
            do {
                val next = native.next(key)
                if (next == LuaJValue.NIL) {
                    break
                }
                key = next.arg(1)
                map[of(next.arg(1))] = of(next.arg(2))
            } while (true)
            return map.toString()
        }

        actual val size: Int
            get() = native.rawlen()
    }

    actual companion object {
        actual fun of(value: Double): Number = Number(value)
        actual fun of(value: Long): LuaInt = LuaInt(value)
        actual fun of(value: kotlin.Boolean): Boolean = Boolean(value)
        actual fun of(value: kotlin.String): String = String(value)
        actual fun of(table: Map<LuaValue, LuaValue>): Table = Table(table)
        actual fun of(table: HashMap<LuaValue, LuaValue>): Table = Table(table)
        fun of(value: LuaJValue): LuaValue =
            when (value.type()) {
                LuaJValue.TNUMBER -> Number(value.checknumber())
                LuaJValue.TINT -> LuaInt(value.checknumber())
                LuaJValue.TBOOLEAN -> Boolean(value.checkboolean())
                LuaJValue.TSTRING -> String(value.checkjstring())
                LuaJValue.TTABLE -> Table(value.checktable())
                LuaJValue.TFUNCTION -> Function(value.checkfunction())
                LuaJValue.TNIL -> Nil
                else -> throw IllegalArgumentException("Unknown type ${value.typename()}")
            }
    }
}

private fun buildTableFromMap(map: Map<LuaValue, LuaValue>): LuaTable =
    LuaJValue.tableOf(
        map.entries.asSequence()
            .flatMap { sequenceOf(it.key.native, it.value.native) }
            .toList()
            .toList()
            .toTypedArray()
    )