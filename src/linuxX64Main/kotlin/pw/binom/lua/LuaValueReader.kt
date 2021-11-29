package pw.binom.lua

import kotlinx.cinterop.toKString
import platform.internal_lua.*
import kotlin.math.absoluteValue

fun LuaState.readValue(index: Int, ref: Boolean): LuaValue {
    val index = if (index.absoluteValue <= 255 && index < 0) lua_gettop(this) + index + 1 else index
    return when (val type = lua_type(this, index)) {
        LUA_TNONE, LUA_TNIL -> LuaValue.Nil
        LUA_TNUMBER -> LuaValue.Number(lua_tonumberx(this, index, null))
        LUA_TBOOLEAN -> LuaValue.Boolean(lua_toboolean(this, index) != 0)
        LUA_TSTRING -> LuaValue.String(lua_tostring(this, index) ?: "")
        LUA_TFUNCTION -> {
            if (ref) {
                val ref = klua_get_value(this, index)!!
                LuaValue.FunctionRef(ref, this)
            } else {
                var upvalueCount = 0
                while (true) {
                    lua_getupvalue(this, index, upvalueCount + 1) ?: break
                    upvalueCount++
                }
                val upvalues = if (upvalueCount == 0) {
                    emptyList()
                } else {
                    Array(upvalueCount) {
                        val t = readValue(-1, true)
                        lua_pop(this, 1)
                        t
                    }.reversed()
                }
                val funcPtr = lua_tocfunction(this, index)
                LuaValue.FunctionValue(upvalues = upvalues, ptr = funcPtr)
            }
        }
        LUA_TTABLE -> {
            if (ref) {
                checkState {
                    val ref = klua_get_value(this, index)!!
                    LuaValue.TableRef(ref = ref, state = this)
                }
            } else {
                val map = HashMap<LuaValue, LuaValue>()
                checkState {
                    lua_pushnil(this)
                    while (true) {
                        val c = lua_next(this, index)
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
                    val c = lua_getmetatable(this, index)
                    if (c != 0) {
                        val value = readValue(-1, true)
                        lua_pop(this, 1)
                        value
                    } else {
                        LuaValue.Nil
                    }
                }
                return LuaValue.TableValue(map, metaTable)
            }
        }
//        LUA_TUSERDATA -> TODO("User data not supported")
        LUA_TTHREAD -> TODO("Thread not supported")
        LUA_TUSERDATA->{
            val ref = klua_get_value(this, index)!!
            LuaValue.UserData(ref = ref, state = this)
        }
        LUA_TLIGHTUSERDATA -> {
            val c = lua_touserdata(this, index)
            LuaValue.LightUserData(c)
        }
        else -> {
            val typename = lua_typename(this, type)?.toKString()
            throw RuntimeException("Unknown lua type: $typename (Code $type)")
        }
    }
}