package pw.binom.lua

import kotlinx.cinterop.*
import platform.internal_lua.*

actual sealed interface LuaValue {
    actual class FunctionValue(val ptr: lua_CFunction?, val upvalues: List<LuaValue>) : LuaValue {
        override fun toString(): kotlin.String = "Function(${upvalues})"
    }

    actual interface Data : LuaValue {
        actual val value: Any?
        fun dispose()
    }

    actual class UserData(override val ref: CPointer<TValue>, val state: LuaState) : LuaValue, Ref, Callable, Meta,
        Data {
        override var metatable: LuaValue
            get() = getMetatable(state, this)
            set(value) {
                setMetatable(state, this, value)
            }

        override fun call(vararg args: LuaValue): List<LuaValue> {
            state.pushValue(this)
            return state.call(*args)
        }

        val link: klua_pointer
            get() {
                state.checkState {
                    state.pushValue(this)
                    val ptrLink = lua_touserdata(state, -1)!!
                    state.pop(1)
                    return ptrLink.reinterpret<klua_pointer>().pointed
                }
            }

        val ptr: COpaquePointer?
            get() = link.pointer

        override val value: Any?
            get() = ptr.toKotlinObject()

        override fun dispose() {
            val ptr = link.pointer ?: return
            ptr.asStableRef<Any>().dispose()
            link.pointer = null
        }

        actual val toLightUserData: LightUserData
            get() = LightUserData(ptr)

        override fun toString(): kotlin.String = "UserData(${ref.toLong().toString(16)})"
    }

    actual class LightUserData(var lightPtr: COpaquePointer?) : Data {
        override val value: Any?
            get() = lightPtr.toKotlinObject()

        override fun dispose() {
            val ptr = lightPtr ?: return
            ptr.asStableRef<Any>().dispose()
            lightPtr = null
        }

        override fun toString(): kotlin.String = "LightUserData(${lightPtr?.toLong()?.toString(16)})"
    }

    //    actual class Function(val ptr: lua_CFunction?, val upvalues: List<LuaValue>) : LuaValue
    actual class Number actual constructor(actual val value: Double) : LuaValue {
        override fun toString(): kotlin.String = value.toString()
        override fun hashCode(): Int = value.hashCode()
        override fun equals(other: Any?): kotlin.Boolean = value == other
    }

    actual class LuaInt actual constructor(actual val value: Long) : LuaValue {
        override fun toString(): kotlin.String = value.toString()
        override fun hashCode(): Int = value.hashCode()
        override fun equals(other: Any?): kotlin.Boolean = value == other
    }

    actual class Boolean actual constructor(actual val value: kotlin.Boolean) : LuaValue {
        override fun toString(): kotlin.String = value.toString()
        override fun hashCode(): Int = value.hashCode()
        override fun equals(other: Any?): kotlin.Boolean = value == other
    }

    actual class String actual constructor(actual val value: kotlin.String) : LuaValue {
        override fun toString(): kotlin.String = value.toString()
        override fun hashCode(): Int = value.hashCode()
        override fun equals(other: Any?): kotlin.Boolean = value == other
    }

    actual sealed interface Ref : LuaValue {
        val ref: CPointer<TValue>
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

    actual interface Meta : LuaValue {
        actual var metatable: LuaValue
//        actual fun getMetatable(): LuaValue
//        actual fun setMetatable(table: LuaValue)
    }

    actual class TableRef(override val ref: CPointer<TValue>, val state: LuaState) : Ref, Table, Callable, Meta {
        override operator fun get(key: LuaValue): LuaValue {
            state.checkState {
                state.pushValue(this)
                state.pushValue(key)
                lua_gettable(state, -2)
                val value = state.readValue(-1, ref = true)
                lua_pop(state, 2)
                return value
            }
        }

        override operator fun set(key: LuaValue, value: LuaValue) {
            state.checkState {
                state.pushValue(this)
                state.pushValue(key)
                state.pushValue(value)
                lua_settable(state, -3)
                lua_pop(state, 1)
            }
        }

        override fun rawGet(key: LuaValue): LuaValue {
            state.checkState {
                state.pushValue(this)
                state.pushValue(key)
                lua_rawget(state, -2)
                val value = state.readValue(-1, true)
                lua_pop(state, 1)
                return value
            }
        }

        override fun rawSet(key: LuaValue, value: LuaValue) {
            state.checkState {
                state.pushValue(this)
                state.pushValue(key)
                state.pushValue(value)
                lua_rawset(state, -3)
                lua_pop(state, 1)
            }
        }

        override val size
            get(): LuaValue {
                state.checkState {
                    state.pushValue(this)
                    lua_len(state, -1)
                    val value = state.readValue(-1, true)
                    lua_pop(state, 1)
                    return value
                }
            }

        override val rawSize: Int
            get() {
                state.checkState {
                    state.pushValue(this)
                    val len = lua_rawlen(state, -1)
                    lua_pop(state, 1)
                    return len.convert()
                }
            }

        override fun toMap(): Map<LuaValue, LuaValue> =
            toValue().toMap()

        override fun call(vararg args: LuaValue): List<LuaValue> {
            state.pushValue(this)
            return state.call(*args)
        }

        override var metatable: LuaValue
            get() = getMetatable(state, this)
            set(value) {
                setMetatable(state, this, value)
            }

        override fun equals(other: Any?): kotlin.Boolean {
            other ?: return false
            if (this === other) return true
            if (this::class !== other::class) return false

            other as TableRef

            if (ref != other.ref) return false

            return true
        }

        override fun hashCode(): Int {
            return ref.hashCode()
        }

        override fun toString(): kotlin.String =
            "TableRef(${ref.toLong().toString(16)})"

        actual fun toValue(): TableValue {
            val t = state.checkState {
                state.pushValue(this)
                val result = state.readValue(-1, false)
                state.pop(1)
                result
            }
            return t as TableValue
        }

    }

    actual interface Callable : LuaValue {
        actual fun call(vararg args: LuaValue): List<LuaValue>
    }

    actual class FunctionRef(override val ref: CPointer<TValue>, val state: LuaState) : Ref, Callable {

        override fun toString(): kotlin.String = "FunctionRef(${ref.toLong().toString(16)})"

        actual override fun call(vararg args: LuaValue): List<LuaValue> {
            state.pushValue(this)
            return state.call(*args)
        }

        actual fun toValue(): FunctionValue =
            state.checkState {
                state.pushValue(this)
                val ret = state.readValue(-1, ref = false) as FunctionValue
                state.pop(1)
                ret
            }

        override fun equals(other: Any?): kotlin.Boolean {
            if (other !is FunctionRef) {
                return false
            }
            return other.ref === ref
        }

        override fun hashCode(): Int = ref.toLong().toInt()
    }

    actual class TableValue constructor(val map: HashMap<LuaValue, LuaValue>, override var metatable: LuaValue) :
        LuaValue, Table, Meta {
        actual constructor(map: Map<LuaValue, LuaValue>) : this(HashMap(map), LuaValue.Nil)
        actual constructor(vararg keys: Pair<LuaValue, LuaValue>) : this(keys.toMap())
        actual constructor() : this(HashMap())

        override fun toString(): kotlin.String =
            if (metatable == Nil) {
                "TableValue(${toMap()})"
            } else {
                "TableValue(${toMap()}, metatable: $metatable)"
            }

        override val rawSize: Int
            get() = map.size

        override val size: LuaValue
            get() = LuaInt(rawSize.toLong())

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

        override fun toMap(): Map<LuaValue, LuaValue> = map
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
        actual fun of(
            table: Map<LuaValue, LuaValue>,
            metatable: LuaValue
        ): TableValue =
            TableValue(HashMap(table), metatable)
    }
}

private fun getMetatable(state: LuaState, value: LuaValue.Meta): LuaValue {
    val table = state.checkState {
        state.pushValue(value)
        if (lua_getmetatable(state, -1) != 0) {
            val s = state.readValue(-1, true)
            lua_pop(state, 2)
            s
        } else {
            lua_pop(state, 1)
            LuaValue.Nil
        }
    }
    return table
}

private fun setMetatable(state: LuaState, value: LuaValue.Meta, table: LuaValue) {
    state.checkState {
        state.pushValue(value)
        state.pushValue(table)
        lua_setmetatable(state, -2)
        state.pop(1)
    }
}

private fun COpaquePointer?.toKotlinObject() =
    if (this == null || this.toLong() == 0L) {
        null
    } else {
        this.asStableRef<Any>().get()
    }