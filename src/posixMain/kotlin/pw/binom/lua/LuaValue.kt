@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package pw.binom.lua

import kotlinx.cinterop.*
import platform.internal_lua.*
import pw.binom.lua.readValue

actual sealed interface LuaValue {
    actual class FunctionValue(val ptr: lua_CFunction1?, val upvalues: List<LuaValue>) : LuaValue {
        override fun toString(): kotlin.String = "function_value(${ptr.strPtr()}, $upvalues)"
    }

    actual interface Data : LuaValue {
        actual val value: Any?
        fun dispose()
    }

    actual class UserData internal constructor(override val ref: LuaRef, internal val ll: LuaStateAndLib) :
        RefObject,
        Data {
        private val cleaner = createCleaner1(ll, ref)

        actual override var metatable: LuaValue
            get() = getMetatable(ll, this)
            set(value) {
                setMetatable(ll, this, value)
            }

        actual override fun call(vararg args: LuaValue): List<LuaValue> {
            ll.pushValue(this)
            return ll.callClosure(*args)
        }

//        val link: COpaquePointer1
//            get() {
//                state.checkState {
//                    state.pushValue(this)
//                    val ptrLink = lua_touserdata1(state, -1)!!
//                    state.pop(1)
//                    return getPtrFromPtr(heap,ptrLink)!!
// //                    return ptrLink.reinterpret<klua_pointer>().pointed
//                }
//            }

        val lk: COpaquePointer
            get() =
                ll.state.checkState {
                    ll.pushValue(this)
                    val ptrLink = lua_touserdata(ll.state, -1)!!
                    lua_pop(ll.state,1)
                    return ptrLink
                }

        val ptr: COpaquePointer?
            get() = Heap.getPtrFromPtr(lk)

        actual override val value: Any?
            get() = ptr.toKotlinObject()

        override fun dispose() {
            val ptr = ptr ?: return
            ptr.asStableRef<Any>().dispose()
            Heap.setPtrFromPtr(lk, null)
        }

        actual val toLightUserData: LightUserData
            get() = LightUserData(ptr)

        actual override fun callToString() =
            callToString(ll)

        override fun toString(): kotlin.String = "userdata(${ptr.strPtr()})"
    }

    @OptIn(ExperimentalForeignApi::class)
    actual class LightUserData(var lightPtr: COpaquePointer?) : Data {
        actual constructor(value: Any?) : this(value?.let { StableRef.create(it).asCPointer() })

        actual override val value: Any?
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
        override fun equals(other: Any?): kotlin.Boolean {
            if (other == null || other !is Number) {
                return false
            }
            return value == other.value
        }
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
        actual fun toList(): List<LuaValue>
    }

    actual interface Meta : LuaValue {
        actual var metatable: LuaValue
    }

    actual sealed interface RefObject : Ref, Callable, Meta {
        actual fun callToString(): kotlin.String
    }

    @OptIn(ExperimentalForeignApi::class)
    actual class TableRef internal constructor(
        override val ref: LuaRef,
        val ptr: COpaquePointer,
        internal val ll: LuaStateAndLib,
    ) : Table, RefObject {
        private val cleaner = createCleaner1(ll, ref)

        actual override operator fun get(key: LuaValue): LuaValue {
            ll.state.checkState {
                ll.pushValue(this)
                ll.pushValue(key)
                lua_gettable(ll.state, -2)
                val value = ll.readValue(-1, ref = true)
                lua_pop(ll.state,2)
                return value
            }
        }

        actual override operator fun set(key: LuaValue, value: LuaValue) {
            ll.state.checkState {
                ll.pushValue(this)
                ll.pushValue(key)
                ll.pushValue(value)
                lua_settable(ll.state, -3)
                lua_pop(ll.state,1)
            }
        }

        actual override fun rawGet(key: LuaValue): LuaValue {
            ll.state.checkState {
                ll.pushValue(this)
                ll.pushValue(key)
                lua_rawget(ll.state, -2)
                val value = ll.readValue(-1, true)
                lua_pop(ll.state,1)
                return value
            }
        }

        actual override fun rawSet(key: LuaValue, value: LuaValue) {
            ll.state.checkState {
                ll.pushValue(this)
                ll.pushValue(key)
                ll.pushValue(value)
                lua_rawset(ll.state, -3)
                lua_pop(ll.state,1)
            }
        }

        actual override val size
            get(): LuaValue {
                ll.state.checkState {
                    ll.pushValue(this)
                    lua_len(ll.state, -1)
                    val value = ll.readValue(-1, true)
                    lua_pop(ll.state,1)
                    return value
                }
            }

        actual override val rawSize: Int
            get() {
                ll.state.checkState {
                    ll.pushValue(this)
                    val len = lua_rawlen(ll.state, -1).toInt()
                    lua_pop(ll.state,1)
                    return len.toInt()
                }
            }

        actual override fun toMap(): Map<LuaValue, LuaValue> =
            toValue().toMap()

        actual override fun call(vararg args: LuaValue): List<LuaValue> {
            ll.pushValue(this)
            return ll.callClosure(*args)
        }

        actual override var metatable: LuaValue
            get() = getMetatable(ll, this)
            set(value) {
                setMetatable(ll, this, value)
            }

        actual override fun callToString() =
            callToString(ll)

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

        actual override fun toValue(): TableValue {
            val t = ll.state.checkState {
                ll.pushValue(this)
                val result = ll.readValue(-1, false)
                lua_pop(ll.state,1)
                result
            }
            return t as TableValue
        }

        actual override fun toList(): List<LuaValue> = (1..rawSize).map {
            get(of(it.toLong()))
        }
    }

    actual interface Callable : LuaValue {
        actual fun call(vararg args: LuaValue): List<LuaValue>
    }

    actual class FunctionRef internal constructor(
        override val ref: LuaRef,
        val ptr: COpaquePointer,
        internal val ll: LuaStateAndLib,
    ) : Ref, Callable {
        private val cleaner = createCleaner1(ll, ref)

        override fun toString(): kotlin.String = "function(${ptr.strPtr()})"

        actual override fun call(vararg args: LuaValue): List<LuaValue> {
            ll.pushValue(this)
            return ll.callClosure(*args)
        }

        actual fun toValue(): FunctionValue =
            ll.state.checkState {
                ll.pushValue(this)
                val ret = ll.readValue(-1, ref = false) as FunctionValue
                lua_pop(ll.state,1)
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

    actual class TableValue constructor(val map: HashMap<LuaValue, LuaValue>, actual override var metatable: LuaValue) :
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

        actual override val rawSize: Int
            get() = map.size

        actual override val size: LuaValue
            get() = LuaInt(rawSize.toLong())

        actual override fun rawGet(key: LuaValue): LuaValue = map[key] ?: Nil

        actual override fun rawSet(key: LuaValue, value: LuaValue) {
            if (value is Nil) {
                map.remove(key)
            } else {
                map[key] = value
            }
        }

        actual override fun set(key: LuaValue, value: LuaValue) {
            rawSet(key, value)
        }

        actual override fun get(key: LuaValue): LuaValue =
            rawGet(key)

        actual override fun toValue(): TableValue = this
        actual override fun toList(): List<LuaValue> =
            (1..rawSize).map {
                map[of(it.toLong())] ?: Nil
            }

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
        actual fun of(
            table: Map<LuaValue, LuaValue>,
            metatable: LuaValue,
        ): TableValue =
            TableValue(HashMap(table), metatable)

        actual fun of(table: List<LuaValue>): TableValue {
            val result = HashMap<LuaValue, LuaValue>()
            table.forEachIndexed { index, luaValue ->
                result[of(index.toLong() + 1)] = luaValue
            }
            return TableValue(result)
        }

        actual fun of(table: Array<LuaValue>): TableValue {
            val result = HashMap<LuaValue, LuaValue>()
            table.forEachIndexed { index, luaValue ->
                result[of(index.toLong() + 1)] = luaValue
            }
            return TableValue(result)
        }

        actual fun of(
            table: List<LuaValue>,
            metatable: LuaValue,
        ): TableValue {
            val result = HashMap<LuaValue, LuaValue>()
            table.forEachIndexed { index, luaValue ->
                result[of(index.toLong() + 1)] = luaValue
            }
            return TableValue(result, metatable = metatable)
        }
    }
}

private fun getMetatable(ll: LuaStateAndLib, value: LuaValue.Meta): LuaValue {
    val table = ll.state.checkState {
        ll.pushValue(value)
        if (lua_getmetatable(ll.state, -1) != 0) {
            val s = ll.readValue(-1, true)
            lua_pop(ll.state,2)
            s
        } else {
            lua_pop(ll.state,1)
            LuaValue.Nil
        }
    }
    return table
}

private fun setMetatable(ll: LuaStateAndLib, value: LuaValue.Meta, table: LuaValue) {
    ll.state.checkState {
        ll.pushValue(value)
        ll.pushValue(table)
        lua_setmetatable(ll.state, -2)
        lua_pop(ll.state,1)
    }
}

private fun LuaValue.RefObject.callToString(ll: LuaStateAndLib): kotlin.String =
    ll.state.checkState {
        ll.pushValue(this)
        val str = luaL_tolstring(ll.state, -1,null)?.toKString()
        lua_pop(ll.state,2)
        return str ?: ""
    }

private fun COpaquePointer?.toKotlinObject() =
    if (this == null || this.toLong() == 0L) {
        null
    } else {
        this.asStableRef<Any>().get()
    }
