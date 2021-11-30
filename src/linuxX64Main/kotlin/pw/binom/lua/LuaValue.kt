@file:OptIn(ExperimentalStdlibApi::class)

package pw.binom.lua

import kotlinx.cinterop.*
import platform.internal_lua.*
import kotlin.native.internal.createCleaner

actual sealed interface LuaValue {
    actual class FunctionValue(val ptr: lua_CFunction?, val upvalues: List<LuaValue>) : LuaValue {
        override fun toString(): kotlin.String = "function_value(${ptr.strPtr()}, $upvalues)"
    }

    actual interface Data : LuaValue {
        actual val value: Any?
        fun dispose()
    }

    actual class UserData(override val ref: LuaRef, val state: LuaState) : RefObject, Data {

        private val cleaner = createCleaner(state to ref) {
            it.first.disposeRef(it.second)
        }

        override var metatable: LuaValue
            get() = getMetatable(state, this)
            set(value) {
                setMetatable(state, this, value)
            }

        override fun call(vararg args: LuaValue): List<LuaValue> {
            state.pushValue(this)
            return state.callClosure(*args)
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

        override fun callToString() =
            callToString(state)

        override fun toString(): kotlin.String = "userdata(${ptr.strPtr()})"
    }

    actual class LightUserData(var lightPtr: COpaquePointer?) : Data {
        override val value: Any?
            get() = lightPtr.toKotlinObject()

        override fun dispose() {
            val ptr = lightPtr ?: return
            ptr.asStableRef<Any>().dispose()
            lightPtr = null
        }

        override fun toString(): kotlin.String = "lightuserdata(${lightPtr.strPtr()})"
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
        val ref: LuaRef
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

    actual interface Meta : LuaValue {
        actual var metatable: LuaValue
    }

    actual sealed interface RefObject : Ref, Callable, Meta {
        actual fun callToString(): kotlin.String
    }

    actual class TableRef(override val ref: LuaRef, val ptr: COpaquePointer, val state: LuaState) : Table, RefObject {
        private val cleaner = createCleaner(state to ref) {
            it.first.disposeRef(it.second)
        }

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
            return state.callClosure(*args)
        }

        override var metatable: LuaValue
            get() = getMetatable(state, this)
            set(value) {
                setMetatable(state, this, value)
            }

        override fun callToString() =
            callToString(state)

        override fun equals(other: Any?): kotlin.Boolean {
            other ?: return false
            if (this === other) return true
            if (this::class !== other::class) return false

            other as TableRef

            if (ptr != other.ptr) return false

            return true
        }

        override fun hashCode(): Int {
            return ptr.hashCode()
        }

        override fun toString(): kotlin.String =
            "table(${ptr.strPtr()})"

        override fun toValue(): TableValue {
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

    actual class FunctionRef(override val ref: LuaRef, val ptr: COpaquePointer, val state: LuaState) : Ref, Callable {

        private val cleaner = createCleaner(state to ref) {
            it.first.disposeRef(it.second)
        }

        override fun toString(): kotlin.String = "function(${ptr.strPtr()})"

        actual override fun call(vararg args: LuaValue): List<LuaValue> {
            state.pushValue(this)
            return state.callClosure(*args)
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
            return other.ref.id == ref.id
        }

        override fun hashCode(): Int = ref.id
    }

    actual class TableValue constructor(val map: HashMap<LuaValue, LuaValue>, override var metatable: LuaValue) :
        LuaValue, Table, Meta {
        actual constructor(map: Map<LuaValue, LuaValue>) : this(HashMap(map), Nil)
        actual constructor(vararg keys: Pair<LuaValue, LuaValue>) : this(keys.toMap())
        actual constructor() : this(HashMap())

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

private fun LuaValue.RefObject.callToString(state: LuaState): kotlin.String =
    state.checkState {
        state.pushValue(this)
        luaL_tolstring(state, -1, null)
        val result = state.readValue(-1, ref = true)
        state.pop(2)
        result.toString()
    }

private fun COpaquePointer?.toKotlinObject() =
    if (this == null || this.toLong() == 0L) {
        null
    } else {
        this.asStableRef<Any>().get()
    }