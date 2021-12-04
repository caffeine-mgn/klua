package pw.binom.lua

import kotlin.math.absoluteValue

internal inline fun LuaLib.lua_pcall1(L: LuaState, n: Int, r: Int, f: Int) =
    lua_pcallk1(L, (n), (r), (f), 0, null)

internal inline fun LuaLib.lua_upvalueindex1(i: Int) = (LUA_REGISTRYINDEX1 - (i))
internal inline fun LuaLib.lua_pop1(L: LuaState, n: Int) = lua_settop1(L, -(n) - 1)
internal inline fun LuaLib.lua_isnil1(L: LuaState, n: Int) = (lua_type1(L, (n)) == LUA_TNIL1)
internal inline fun LuaLib.lua_isfunction1(L: LuaState, n: Int) = (lua_type1(L, (n)) == LUA_TFUNCTION1)
internal inline fun LuaLib.lua_remove1(L: LuaState, idx: Int) {
    lua_rotate1(L, (idx), -1)
    lua_pop1(L, 1)
}

internal inline fun LuaStateAndLib.pop(size: Int) = lib.lua_pop1(state, size)
internal inline fun LuaLib.lua_newuserdata1(L: LuaState, s: Int) = lua_newuserdatauv1(L, s, 1)
internal inline fun <T> LuaStateAndLib.checkState(func: () -> T): T {
    val top = lib.lua_gettop1(state)
    return try {
        func()
    } finally {
        val newTop = lib.lua_gettop1(state)
        check(newTop == top) { "Invalid Stack Size. Expected: ${top}, Actual: $newTop" }
    }
}
internal inline fun COpaquePointer1?.strPtr() = this?.toLong1()?.toString(16) ?: "0"

internal fun LuaStateAndLib.absoluteStackValue(index:Int)=
    if (index.absoluteValue <= 255 && index < 0) lib.lua_gettop1(state) + index + 1 else index

internal inline fun LuaStateAndLib.makeRef(): LuaRef = LuaRef(lib.luaL_ref1(state, LUA_REGISTRYINDEX1))


internal fun LuaStateAndLib.makeRef(popValue: Boolean): LuaRef =
    if (popValue) {
        makeRef()
    } else {
        lib.lua_pushvalue1(state, -1)
        LuaRef(lib.luaL_ref1(state, LUA_REGISTRYINDEX1))
    }

internal fun LuaStateAndLib.makeRef(index: Int, popValue: Boolean): LuaRef =
    if (lib.lua_gettop1(state) != index) {
        lib.lua_pushvalue1(state, index)
        val ref = makeRef()
        if (popValue) {
            lib.lua_remove1(state, index)
        }
        ref
    } else {
        makeRef(popValue)
    }

internal inline fun LuaStateAndLib.disposeRef(ref: LuaRef) = lib.luaL_unref1(state, LUA_REGISTRYINDEX1, ref.id)
internal inline fun LuaStateAndLib.pushRef(ref: LuaRef) = lib.lua_rawgeti1(state, LUA_REGISTRYINDEX1, ref.id)
internal inline fun LuaStateAndLib.type(index: Int = -1) = lib.lua_type1(state, index)
//internal inline fun LuaState.typeName(index: Int = -1) = lua_typename1(this, type(index))


value class LuaRef(val id:Int)