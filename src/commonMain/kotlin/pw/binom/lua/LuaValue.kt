package pw.binom.lua

expect sealed interface LuaValue {
    class Function : LuaValue
    class Number : LuaValue {
        constructor(value: Double)

        val value: Double
    }

    class LuaInt : LuaValue {
        constructor(value: Long)

        val value: Long
    }

    class Boolean : LuaValue {
        constructor(value: kotlin.Boolean)

        val value: kotlin.Boolean
    }

    class String : LuaValue {
        constructor(value: kotlin.String)

        val value: kotlin.String
    }

    class Table : LuaValue {
        val size: Int
        operator fun get(key: LuaValue): LuaValue
        operator fun set(key: LuaValue, value: LuaValue)

        constructor(map: Map<LuaValue, LuaValue>)
        constructor(vararg keys: Pair<LuaValue, LuaValue>)
        constructor()
    }

    object Nil : LuaValue
    companion object {
        fun of(value: Double): LuaValue.Number
        fun of(value: Long): LuaValue.LuaInt
        fun of(value: kotlin.Boolean): LuaValue.Boolean
        fun of(value: kotlin.String): LuaValue.String
        fun of(table: Map<LuaValue, LuaValue>): LuaValue.Table
        fun of(table: HashMap<LuaValue, LuaValue>): LuaValue.Table
    }
}