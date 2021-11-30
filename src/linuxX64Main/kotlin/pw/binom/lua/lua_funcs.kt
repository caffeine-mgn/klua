package pw.binom.lua

import cnames.structs.lua_State
import kotlinx.cinterop.*
import platform.internal_lua.*
import kotlin.math.absoluteValue

typealias LuaState = CPointer<lua_State>

internal inline fun lua_istable(L: LuaState, n: Int) = (lua_type(L, (n)) == LUA_TTABLE)
internal inline fun lua_call(L: LuaState, n: Int, r: Int) = lua_callk(L, (n), (r), 0, null)
internal inline fun lua_pcall(L: LuaState, n: Int, r: Int, f: Int) =
    lua_pcallk(L, (n), (r), (f), 0, null)

internal inline fun lua_upvalueindex(i: Int) = (LUA_REGISTRYINDEX - (i))
internal inline fun lua_pop(L: LuaState, n: Int) = lua_settop(L, -(n) - 1)
internal inline fun lua_newtable(L: LuaState) = lua_createtable(L, 0, 0)
internal inline fun lua_isnil(L: LuaState, n: Int) = (lua_type(L, (n)) == LUA_TNIL)
internal inline fun lua_isfunction(L: LuaState, n: Int) = (lua_type(L, (n)) == LUA_TFUNCTION)
internal inline fun lua_register(L: LuaState, n: String, f: lua_CFunction) {
    lua_pushcfunction(L, (f))
    lua_setglobal(L, n)
}

internal inline fun lua_remove(L: LuaState, idx: Int) {
    lua_rotate(L, (idx), -1)
    lua_pop(L, 1)
}

internal inline fun lua_replace(L: LuaState, idx: Int) {
    lua_copy(L, -1, (idx))
    lua_pop(L, 1)
}

internal inline fun lua_pushcfunction(L: LuaState, f: lua_CFunction) {
    lua_pushcclosure(L, (f), 0)
}

internal inline fun lua_tostring(L: LuaState, i: Int) = lua_tolstring(L, (i), null)?.toKString()
internal inline fun LuaState.pop(size: Int) = lua_pop(this, size)
internal inline fun lua_newuserdata(L: LuaState, s: Int) = lua_newuserdatauv(L, s.convert(), 1)

internal inline fun <T> LuaState.checkState(func: () -> T): T {
    val top = lua_gettop(this)
    return try {
        func()
    } finally {
        val newTop = lua_gettop(this)
        check(newTop == top) { "Invalid Stack Size. Expected: ${top}, Actual: $newTop" }
    }
}

internal inline fun COpaquePointer?.strPtr() = this?.toLong()?.toString(16) ?: "0"

internal fun LuaState.absoluteStackValue(index:Int)=
    if (index.absoluteValue <= 255 && index < 0) lua_gettop(this) + index + 1 else index

//internal inline fun LuaState.isValid(value: CPointer<TValue>) = klua_isvalid(this, value) != 0

//internal val CPointer<TValue>.type: Int
//    get() = pointed.tt_.toInt()
//
//internal val CPointer<TValue>.typeName: String?
//    get() {
//        val t = type
//        return if (t < 0 || t > LUA_TOTALTYPES) {
//            null
//        } else {
//            luaT_typenames_[t + 1]!!.toKString()
//        }
//    }

internal inline fun LuaState.makeRef(): LuaRef = LuaRef(luaL_ref(this, LUA_REGISTRYINDEX))
internal fun LuaState.makeRef(popValue: Boolean): LuaRef =
    if (popValue) {
        makeRef()
    } else {
        lua_pushvalue(this, -1)
        LuaRef(luaL_ref(this, LUA_REGISTRYINDEX))
    }


internal fun LuaState.makeRef(index: Int, popValue: Boolean): LuaRef =
    if (lua_gettop(this) != index) {
        lua_pushvalue(this, index)
        val ref = makeRef()
        if (popValue) {
            lua_remove(this, index)
        }
        ref
    } else {
        makeRef(popValue)
    }

internal inline fun LuaState.disposeRef(ref: LuaRef) = luaL_unref(this, LUA_REGISTRYINDEX, ref.id)
internal inline fun LuaState.pushRef(ref: LuaRef) = lua_rawgeti(this, LUA_REGISTRYINDEX, ref.id.convert())
internal inline fun LuaState.type(index: Int = -1) = lua_type(this, index)
internal inline fun LuaState.typeName(index: Int = -1) = lua_typename(this, type(index))

value class LuaRef(val id:Int)