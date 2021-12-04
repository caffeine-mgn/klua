package pw.binom.lua

expect sealed interface LuaValue {
    class FunctionValue : LuaValue
    class Number : LuaValue {
        constructor(value: Double)

        val value: Double
    }

    interface Data : LuaValue {
        val value: Any?
    }

    class UserData : RefObject, Data {
        override var metatable: LuaValue
        val toLightUserData: LightUserData
    }

    class LightUserData : Data {
        override val value: Any?
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

    interface Callable : LuaValue {
        fun call(vararg args: LuaValue): List<LuaValue>
    }

    interface Meta : LuaValue {
        var metatable: LuaValue
    }

    interface Table : LuaValue {
        val rawSize: Int
        val size: LuaValue
        fun toMap(): Map<LuaValue, LuaValue>
        fun rawGet(key: LuaValue): LuaValue
        fun rawSet(key: LuaValue, value: LuaValue)
        operator fun set(key: LuaValue, value: LuaValue)
        operator fun get(key: LuaValue): LuaValue
        fun toValue(): TableValue
    }

    sealed interface Ref : LuaValue

    sealed interface RefObject : Ref, Callable, Meta {
        fun callToString(): kotlin.String
    }

    class TableValue : LuaValue, Table, Meta {
        override var metatable: LuaValue
        constructor(map: Map<LuaValue, LuaValue>)
        constructor(vararg keys: Pair<LuaValue, LuaValue>)
        constructor()
    }

    class TableRef : Table, RefObject {
        override var metatable: LuaValue
        override fun call(vararg args: LuaValue): List<LuaValue>
    }

    class FunctionRef : Ref, Callable {
        override fun call(vararg args: LuaValue): List<LuaValue>
        fun toValue(): FunctionValue
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