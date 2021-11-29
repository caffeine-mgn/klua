package pw.binom.lua

expect sealed interface LuaValue {
    class FunctionValue : LuaValue
    class Number : LuaValue {
        constructor(value: Double)

        val value: Double
    }

    class UserData : LuaValue

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

    class TableValue : LuaValue, Table, Meta {
        override fun rawGet(key: LuaValue): LuaValue
        override fun rawSet(key: LuaValue, value: LuaValue)
        override fun toMap(): Map<LuaValue, LuaValue>

        constructor(map: Map<LuaValue, LuaValue>)
        constructor(vararg keys: Pair<LuaValue, LuaValue>)
        constructor()
    }

    interface Callable : LuaValue {
        fun call(vararg args: LuaValue): List<LuaValue>
    }

    interface Meta : LuaValue {
        var metatable: LuaValue
//        fun getMetatable(): LuaValue
//        fun setMetatable(table: LuaValue)
    }

    interface Table : LuaValue {
        val rawSize: Int
        fun toMap(): Map<LuaValue, LuaValue>
        fun rawGet(key: LuaValue): LuaValue
        fun rawSet(key: LuaValue, value: LuaValue)
    }

    sealed interface Ref : LuaValue {
    }

    class TableRef : Ref, Table, Callable, Meta {
        fun size(): LuaValue
        fun value():TableValue
    }

    class FunctionRef : Ref, Callable {
        override fun call(vararg args: LuaValue): List<LuaValue>
    }

    object Nil : LuaValue
    companion object {
        fun of(value: Double): LuaValue.Number
        fun of(value: Long): LuaValue.LuaInt
        fun of(value: kotlin.Boolean): LuaValue.Boolean
        fun of(value: kotlin.String): LuaValue.String
        fun of(table: Map<LuaValue, LuaValue>): LuaValue.TableValue
        fun of(table: Map<LuaValue, LuaValue>, metatable: LuaValue): LuaValue.TableValue
    }
}