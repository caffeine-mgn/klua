package pw.binom.lua

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

        override fun call(vararg args: LuaValue): List<LuaValue> {
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

        val lk: COpaquePointer1
            get() =
                ll.checkState {
                    ll.pushValue(this)
                    val ptrLink = ll.lib.lua_touserdata1(ll.state, -1)!!
                    ll.pop(1)
                    return ptrLink
                }

        val ptr: COpaquePointer1?
            get() = ll.lib.heap.getPtrFromPtr(lk)

        override val value: Any?
            get() = ptr.toKotlinObject()

        override fun dispose() {
            val ptr = ptr ?: return
            ptr.asStableRef1<Any>().dispose()
            ll.lib.heap.setPtrFromPtr(lk, null)
        }

        actual val toLightUserData: LightUserData
            get() = LightUserData(ptr)

        override fun callToString() =
            callToString(ll)

        override fun toString(): kotlin.String = "userdata(${ptr.strPtr()})"
    }

    actual class LightUserData(var lightPtr: COpaquePointer1?) : Data {
        actual constructor(value:Any?):this(value?.let { StableRef1.create(it).asCPointer()})
        actual override val value: Any?
            get() = lightPtr.toKotlinObject()

        override fun dispose() {
            val ptr = lightPtr ?: return
            ptr.asStableRef1<Any>().dispose()
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

    actual class TableRef internal constructor(
        override val ref: LuaRef,
        val ptr: COpaquePointer1,
        internal val ll: LuaStateAndLib
    ) : Table, RefObject {
        private val cleaner = createCleaner1(ll, ref)

        override operator fun get(key: LuaValue): LuaValue {
            ll.checkState {
                ll.pushValue(this)
                ll.pushValue(key)
                ll.lib.lua_gettable1(ll.state, -2)
                val value = ll.readValue(-1, ref = true)
                ll.pop(2)
                return value
            }
        }

        override operator fun set(key: LuaValue, value: LuaValue) {
            ll.checkState {
                ll.pushValue(this)
                ll.pushValue(key)
                ll.pushValue(value)
                ll.lib.lua_settable1(ll.state, -3)
                ll.pop(1)
            }
        }

        override fun rawGet(key: LuaValue): LuaValue {
            ll.checkState {
                ll.pushValue(this)
                ll.pushValue(key)
                ll.lib.lua_rawget1(ll.state, -2)
                val value = ll.readValue(-1, true)
                ll.pop(1)
                return value
            }
        }

        override fun rawSet(key: LuaValue, value: LuaValue) {
            ll.checkState {
                ll.pushValue(this)
                ll.pushValue(key)
                ll.pushValue(value)
                ll.lib.lua_rawset1(ll.state, -3)
                ll.pop(1)
            }
        }

        override val size
            get(): LuaValue {
                ll.checkState {
                    ll.pushValue(this)
                    ll.lib.lua_len1(ll.state, -1)
                    val value = ll.readValue(-1, true)
                    ll.pop(1)
                    return value
                }
            }

        override val rawSize: Int
            get() {
                ll.checkState {
                    ll.pushValue(this)
                    val len = ll.lib.lua_rawlen1(ll.state, -1)
                    ll.pop(1)
                    return len.toInt()
                }
            }

        override fun toMap(): Map<LuaValue, LuaValue> =
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

        override fun callToString() =
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

        override fun toValue(): TableValue {
            val t = ll.checkState {
                ll.pushValue(this)
                val result = ll.readValue(-1, false)
                ll.pop(1)
                result
            }
            return t as TableValue
        }
    }

    actual interface Callable : LuaValue {
        actual fun call(vararg args: LuaValue): List<LuaValue>
    }

    actual class FunctionRef internal constructor(
        override val ref: LuaRef,
        val ptr: COpaquePointer1,
        internal val ll: LuaStateAndLib
    ) : Ref, Callable {
        private val cleaner = createCleaner1(ll, ref)

        override fun toString(): kotlin.String = "function(${ptr.strPtr()})"

        actual override fun call(vararg args: LuaValue): List<LuaValue> {
            ll.pushValue(this)
            return ll.callClosure(*args)
        }

        actual fun toValue(): FunctionValue =
            ll.checkState {
                ll.pushValue(this)
                val ret = ll.readValue(-1, ref = false) as FunctionValue
                ll.pop(1)
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

private fun getMetatable(ll: LuaStateAndLib, value: LuaValue.Meta): LuaValue {
    val table = ll.checkState {
        ll.pushValue(value)
        if (ll.lib.lua_getmetatable1(ll.state, -1) != 0) {
            val s = ll.readValue(-1, true)
            ll.pop(2)
            s
        } else {
            ll.pop(1)
            LuaValue.Nil
        }
    }
    return table
}

private fun setMetatable(ll: LuaStateAndLib, value: LuaValue.Meta, table: LuaValue) {
    ll.checkState {
        ll.pushValue(value)
        ll.pushValue(table)
        ll.lib.lua_setmetatable1(ll.state, -2)
        ll.pop(1)
    }
}

private fun LuaValue.RefObject.callToString(ll: LuaStateAndLib): kotlin.String =
    ll.checkState {
        ll.pushValue(this)
        val str = ll.lib.luaL_tolstring1(ll.state, -1)
        ll.pop(2)
        return str ?: ""
    }

private fun COpaquePointer1?.toKotlinObject() =
    if (this == null || this.toLong1() == 0L) {
        null
    } else {
        this.asStableRef1<Any>().get()
    }
