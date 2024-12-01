@file:OptIn(ExperimentalForeignApi::class, ExperimentalForeignApi::class)

package pw.binom.lua
import platform.internal_lua.*

import kotlinx.cinterop.ExperimentalForeignApi

internal fun LuaStateAndLib.readValue(index: Int, ref: Boolean): LuaValue {
    val index = absoluteStackValue(index)
    val type = lib.lua_type1(state, index)
    StdOut.info("Read from index=$index")
    return when (type) {
        LUA_TNONE, LUA_TNIL -> LuaValue.Nil
        LUA_TNUMBER -> LuaValue.Number(lib.lua_tonumberx1(state, index))
        LUA_TBOOLEAN -> LuaValue.Boolean(lib.lua_toboolean1(state, index) != 0)
        LUA_TSTRING -> LuaValue.String(lib.lua_tostring1(state, index) ?: "")
        LUA_TFUNCTION -> {
            if (ref) {
                val ref = makeRef(index, popValue = false)
                val ptr = lib.lua_topointer1(state, index)!!
                LuaValue.FunctionRef(ref, ptr = ptr, ll = this)
            } else {
                var upvalueCount = 0
                while (true) {
                    lib.lua_getupvalue1(state, index, upvalueCount + 1) ?: break
                    upvalueCount++
                }
                val upvalues = if (upvalueCount == 0) {
                    emptyList()
                } else {
                    Array(upvalueCount) {
                        val t = readValue(-1, true)
                        lib.lua_pop1(state, 1)
                        t
                    }.reversed()
                }
                val funcPtr = lib.lua_tocfunction1(state, index)
                LuaValue.FunctionValue(upvalues = upvalues, ptr = funcPtr)
            }
        }
        LUA_TTABLE -> {
            if (ref) {
                checkState {
//                    val ref = klua_get_value(this, index)!!
                    val ref = makeRef(index, popValue = false)
                    val ptr = lib.lua_topointer1(state, index)!!
                    LuaValue.TableRef(ref = ref, ptr = ptr, ll = this)
                }
            } else {
                val map = HashMap<LuaValue, LuaValue>()
                checkState {
                    lib.lua_pushnil1(state)
                    while (true) {
                        val c = lib.lua_next1(state, index)
                        if (c == 0) {
                            break
                        }
                        val key = readValue(-2, true)
                        val value = readValue(-1, true)
                        map[key] = value
                        pop(1)
                    }
                }
                val metaTable = checkState {
                    val c = lib.lua_getmetatable1(state, index)
                    if (c != 0) {
                        val value = readValue(-1, true)
                        lib.lua_pop1(state, 1)
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
            val ref = makeRef(index, popValue = false)!!
            LuaValue.UserData(ref = ref, ll = this)
        }
        LUA_TLIGHTUSERDATA -> {
            val c = lib.lua_touserdata1(state, index)
            LuaValue.LightUserData(c)
        }
        else -> {
            val typename: String = "unknown" // TODO()//lua_typename1(this, type)?.toKString()
            throw RuntimeException("Unknown lua type: $typename (Code $type)")
        }
    }
}
