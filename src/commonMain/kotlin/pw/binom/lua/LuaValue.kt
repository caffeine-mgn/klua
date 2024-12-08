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
        override val value: Any?
        override fun callToString(): kotlin.String
        override fun call(vararg args: LuaValue): List<LuaValue>
    }

    class LightUserData : Data {
        /**
         * Creates LightUserData to [value]. On native platform calls `StableRef.create(value)`. Make sure you
         * call `StableRef.dispose()`. Otherwise you got memory leak
         */
        constructor(value: Any?)

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
        fun toList(): List<LuaValue>
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
        override val rawSize: Int
        override val size: LuaValue
        override fun toMap(): Map<LuaValue, LuaValue>
        override fun rawGet(key: LuaValue): LuaValue
        override fun rawSet(key: LuaValue, value: LuaValue)
        override fun set(key: LuaValue, value: LuaValue)
        override fun get(key: LuaValue): LuaValue
        override fun toValue(): TableValue


        constructor(map: Map<LuaValue, LuaValue>)
        constructor(vararg keys: Pair<LuaValue, LuaValue>)
        constructor()
    }

    class TableRef : Table, RefObject {
        override val rawSize: Int
        override fun toValue(): TableValue

        override fun get(key: LuaValue): LuaValue

        override fun set(key: LuaValue, value: LuaValue)

        override fun rawSet(key: LuaValue, value: LuaValue)

        override fun rawGet(key: LuaValue): LuaValue

        override fun toMap(): Map<LuaValue, LuaValue>

        override val size: LuaValue
        override var metatable: LuaValue
        override fun callToString(): kotlin.String

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
        fun of(table: List<LuaValue>): TableValue
        fun of(table: Array<LuaValue>): TableValue
    }
}
