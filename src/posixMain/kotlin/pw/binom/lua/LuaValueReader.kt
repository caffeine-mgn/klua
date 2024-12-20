@file:OptIn(ExperimentalForeignApi::class, ExperimentalForeignApi::class)

package pw.binom.lua
import platform.internal_lua.*

import kotlinx.cinterop.ExperimentalForeignApi

internal fun LuaStateAndLib.readValue(index: Int, ref: Boolean): LuaValue {
    val index = state.absoluteStackValue(index)
    val type = lua_type(state, index)
    return when (type) {
        LUA_TNONE, LUA_TNIL -> LuaValue.Nil
        LUA_TNUMBER -> LuaValue.Number(lua_tonumberx(state, index, null))
        LUA_TBOOLEAN -> LuaValue.Boolean(lua_toboolean(state, index) != 0)
        LUA_TSTRING -> LuaValue.String(lua_tostring(state, index) ?: "")
        LUA_TFUNCTION -> {
            if (ref) {
                val ref = state.makeRef(index, popValue = false)
                val ptr = lua_topointer(state, index)!!
                LuaValue.FunctionRef(ref, ptr = ptr, ll = this)
            } else {
                var upvalueCount = 0
                while (true) {
                    lua_getupvalue(state, index, upvalueCount + 1) ?: break
                    upvalueCount++
                }
                val upvalues = if (upvalueCount == 0) {
                    emptyList()
                } else {
                    Array(upvalueCount) {
                        val t = readValue(-1, true)
                        lua_pop(state, 1)
                        t
                    }.reversed()
                }
                val funcPtr = lua_tocfunction(state, index)
                LuaValue.FunctionValue(upvalues = upvalues, ptr = funcPtr)
            }
        }
        LUA_TTABLE -> {
            if (ref) {
                state.checkState {
//                    val ref = klua_get_value(this, index)!!
                    val ref = state.makeRef(index, popValue = false)
                    val ptr = lua_topointer(state, index)!!
                    LuaValue.TableRef(ref = ref, ptr = ptr, ll = this)
                }
            } else {
                val map = HashMap<LuaValue, LuaValue>()
                state.checkState {
                    lua_pushnil(state)
                    while (true) {
                        val c = lua_next(state, index)
                        if (c == 0) {
                            break
                        }
                        val key = readValue(-2, true)
                        val value = readValue(-1, true)
                        map[key] = value
                        lua_pop(state,1)
                    }
                }
                val metaTable = state.checkState {
                    val c = lua_getmetatable(state, index)
                    if (c != 0) {
                        val value = readValue(-1, true)
                        lua_pop(state, 1)
                        value
                    } else {
                        LuaValue.Nil
                    }
                }
                return LuaValue.TableValue(map, metaTable)
            }
        }
//        LUA_TUSERDATA1 -> TODO("User data not supported")
        LUA_TTHREAD -> TODO("Thread not supported")
        LUA_TUSERDATA -> {
            val ref = state.makeRef(index, popValue = false)
            LuaValue.UserData(ref = ref, ll = this)
        }
        LUA_TLIGHTUSERDATA -> {
            val c = lua_touserdata(state, index)
            LuaValue.LightUserData(c)
        }
        else -> {
            val typename: String = "unknown" // TODO()//lua_typename1(this, type)?.toKString()
            throw RuntimeException("Unknown lua type: $typename (Code $type)")
        }
    }
}
