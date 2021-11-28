@file:OptIn(ExperimentalStdlibApi::class, ExperimentalStdlibApi::class)

package pw.binom.lua

import kotlinx.cinterop.*
import platform.internal_lua.*
import kotlin.native.internal.createCleaner

class Singleton(engine: LuaEngine) {
    val stateInputArgs = StateInputVarargs(engine)
    val stateOutputArgs = StateOutputVarargs(engine)
    val ptr = StableRef.create(this)
    fun dispose() {
        ptr.dispose()
    }
}

actual class LuaEngine {

    internal val state: LuaState = luaL_newstate()!!

    //    private val selfPtr = StableRef.create(this)
    internal val singleton = Singleton(this)
    private val cleaner = createCleaner(singleton) {
        it.dispose()
    }

    private val cleaner2 = createCleaner(state) {
        lua_close(it)
    }

    init {
        luaL_openlibs(state)
    }

    actual operator fun get(name: String): LuaValue {
        lua_getglobal(state, name)
        return readValue(this, -1)
    }

    actual operator fun set(name: String, value: LuaValue) {
        pushValue(this, value)
        lua_setglobal(state, name)
    }

    actual fun dispose() {

    }

    actual fun eval(text: String): List<pw.binom.lua.LuaValue> {
        if (luaL_loadstring(state, text) != 0) {
            throw LuaException("Can't eval text \"$text\"")
        }
        val exitCode = lua_pcall(state, 0, LUA_MULTRET, 0)
        return pcallProcessing(exitCode)
    }

    actual fun call(
        functionName: String,
        vararg args: LuaValue
    ): List<LuaValue> {
        lua_getglobal(state, functionName)
        if (lua_isnil(state, -1)) {
            throw LuaException("Function \"$functionName\" not found")
        }
        if (!lua_isfunction(state, -1)) {
            lua_pop(state, 1)
            throw LuaException("\"$functionName\" is not a function")
        }
        args.forEach {
            pushValue(this, it)
        }
        val exec = lua_pcall(state, args.size, LUA_MULTRET, 0)
        return pcallProcessing(exec)
    }

    private fun pcallProcessing(exeCode: Int): List<LuaValue> {
        when (exeCode) {
            LUA_OK -> {
                val count = lua_gettop(state)
                return (0 until count).map {
                    readValue(this, it + 1)
                }
            }
            LUA_ERRRUN -> {
                val message = if (lua_gettop(state) == 1 && lua_isstring(state, 1) != 0) {
                    val str = lua_tostring(state, 1)
                    lua_pop(state, 1)
                    str
                } else {
                    null
                }
                luaL_traceback(state, state, message, 1)
                val fullMessage = lua_tostring(state, -1)
                lua_pop(state, 1)
                throw LuaException(fullMessage)
            }
            LUA_ERRMEM -> throw RuntimeException("memory allocation error. For such errors, Lua does not call the message handler.")
            LUA_ERRERR -> throw RuntimeException("error while running the message handler.")
            else -> throw RuntimeException("Unknown invoke status")
        }
    }
}

internal inline fun lua_istable(L: LuaState, n: Int) = (lua_type(L, (n)) == LUA_TTABLE)
internal inline fun lua_call(L: LuaState, n: Int, r: Int) = lua_callk(L, (n), (r), 0, null)
//fun traceback (L:LuaState):String? {
//    if (lua_isstring(L, 1)!=1) {  /* 'message' not a string? */
//        println("----#1")
//        return null;  /* keep it intact */
//    }
//    lua_getglobal(L,"debug")
//    if (!lua_istable(L, -1)) {
//        println("----#2")
//        lua_pop(L, 1);
//        return null;
//    }
//    lua_getfield(L, -1, "traceback");
//    if (!lua_isfunction(L, -1)) {
//        println("----#3")
//        lua_pop(L, 2);
//        return null;
//    }
//    lua_pushvalue(L, 1);  /* pass error message */
//    lua_pushinteger(L, 2);  /* skip this function and traceback */
//    lua_call(L, 2, 1);  /* call debug.traceback */
//    val top = lua_gettop(L)
//    println("----#4 top=$top")
//    return if (top == 1 && lua_isstring(L, 1) != 0) {
//        lua_tostring(L, 1)
//    } else {
//        null
//    }
//}

class StateOutputVarargs(val engine: LuaEngine) : OutputVarargs {
    var pushed = 0
        private set

    fun reset() {
        pushed = 0
    }

    override fun plusAssign(value: LuaValue) {
        pushValue(engine, value)
        pushed++
    }

}

class StateInputVarargs(val engine: LuaEngine) : InputVarargs {
    override val size: Int
        get() = lua_gettop(engine.state)

    override fun get(index: Int): LuaValue = readValue(engine, index + 1)
}

private fun pushValue(engine: LuaEngine, value: LuaValue) {
    when (value) {
        is LuaValue.Nil -> lua_pushnil(engine.state)
        is LuaValue.Function -> {
            if (value.implPtr != null) {
                lua_pushlightuserdata(engine.state, engine.singleton.ptr.asCPointer())
                lua_pushlightuserdata(engine.state, value.implPtr)
                lua_pushcclosure(engine.state, userFunction, 2)
            } else {
                lua_pushcclosure(engine.state, value.ptr, 0)
            }
        }
        is LuaValue.Number -> lua_pushnumber(engine.state, value.value)
        is LuaValue.LuaInt -> lua_pushinteger(engine.state, value.value)
        is LuaValue.Boolean -> lua_pushboolean(engine.state, if (value.value) 0 else 1)
        is LuaValue.String -> lua_pushstring(engine.state, value.value)
        is LuaValue.Table -> {
            lua_createtable(engine.state, 0, value.size)
            val table = lua_gettop(engine.state)
            value.map.forEach {
                pushValue(engine, it.key)
                pushValue(engine, it.value)
                lua_settable(engine.state, table);
            }
        }
    }
}

internal inline fun lua_isnil(L: LuaState, n: Int) = (lua_type(L, (n)) == LUA_TNIL)
internal inline fun lua_isfunction(L: LuaState, n: Int) = (lua_type(L, (n)) == LUA_TFUNCTION)

private fun readValue(engine: LuaEngine, index: Int): LuaValue {
    val type = lua_type(engine.state, index)
    if (type == LUA_TNONE) {
        return LuaValue.Nil
    }
    return when (type) {
        LUA_TNIL -> LuaValue.Nil
        LUA_TNUMBER -> LuaValue.Number(lua_tonumberx(engine.state, index, null))
        LUA_TBOOLEAN -> LuaValue.Boolean(lua_toboolean(engine.state, index) != 0)
        LUA_TSTRING -> LuaValue.String(lua_tostring(engine.state, index) ?: "")
        LUA_TFUNCTION -> getUserFunction(engine, index) ?: LuaValue.Function(
            ptr = lua_tocfunction(engine.state, index),
            implPtr = null
        )
        LUA_TTABLE -> {
            lua_pushnil(engine.state)
            val map = HashMap<LuaValue, LuaValue>()
            while (true) {
                val c = lua_next(engine.state, index)
                if (c == 0) {
                    break
                }
                val key = readValue(engine, -2)
                val value = readValue(engine, -1)
                map[key] = value
                lua_pop(engine.state, 1)
            }
            return LuaValue.Table(map)
        }
        LUA_TUSERDATA -> TODO("User data not supported")
        LUA_TTHREAD -> TODO("Thread not supported")
        LUA_TLIGHTUSERDATA -> TODO("light user data not supported")
//        else -> null
        else -> {
            val typename = lua_typename(engine.state, type)?.toKString()
            throw RuntimeException("Unknown lua type: $typename (Code $type)")
        }
    }
}

private fun LuaState.getStackSize() = lua_gettop(this)

private fun LuaState.getStackString(index: Int) = lua_tostring(this, index)

internal inline fun lua_tostring(L: LuaState, i: Int) = lua_tolstring(L, (i), null)?.toKString()

fun lua_pushcfunction(L: LuaState, f: lua_CFunction) {
    lua_pushcclosure(L, (f), 0)
}

fun lua_register(L: LuaState, n: String, f: lua_CFunction) {
    lua_pushcfunction(L, (f))
    lua_setglobal(L, n)
}
//fun lua_register(L: CPointer<cnames.structs.lua_State>)

fun lua_pcall(L: LuaState, n: Int, r: Int, f: Int) =
    lua_pcallk(L, (n), (r), (f), 0, null)

typealias LuaState = CPointer<lua_State>

inline fun lua_upvalueindex(i: Int) = (LUA_REGISTRYINDEX - (i))
internal inline fun lua_pop(L: LuaState, n: Int) {
    lua_settop(L, -(n) - 1)
}

internal inline fun lua_newtable(L: LuaState) = lua_createtable(L, 0, 0)

/**
 * If it is not a user function returns null. User function - is function binded using [UserFunctionContainer]
 */
private fun getUserFunction(engine: LuaEngine, index: Int): LuaValue.Function? {
    if (lua_type(engine.state, index) != LUA_TFUNCTION) {
        //Not a function
        return null
    }
    val cc = lua_getupvalue(engine.state, index, 1)?.toKString()
    if (cc == null) {
        //Function doesn't have upvalue
        return null
    }
    if (lua_type(engine.state, index + 1) != LUA_TLIGHTUSERDATA) {
        //Upvalue is not user-data

        //pop upvalue
        lua_pop(engine.state, 1)
        return null
    }
    val ptr = lua_touserdata(engine.state, index + 1)
    if (engine.singleton.ptr.asCPointer() !== ptr) {
        //pop upvalue
        lua_pop(engine.state, 1)
        return null
    }
    val cc2 = lua_getupvalue(engine.state, index, 2)?.toKString()
    if (cc2 == null) {
        //pop upvalue
        lua_pop(engine.state, 1)
        return null
    }
    if (lua_type(engine.state, index + 2) != LUA_TLIGHTUSERDATA) {
        //Upvalue is not user-data

        //pop upvalue
        //pop upvalue
        lua_pop(engine.state, 2)
        return null
    }
    val functionPtr = lua_touserdata(engine.state, index + 2)
    //pop function
    //pop upvalue
    //pop upvalue
    lua_pop(engine.state, 2)
    return LuaValue.Function(lua_tocfunction(engine.state, index)!!, functionPtr)
}

private val userFunction = staticCFunction<LuaState?, Int> { state ->
    val selfPtr = lua_touserdata(state, lua_upvalueindex(1))?.asStableRef<Singleton>()!!
    val self = selfPtr.get()
    val functionPtr = lua_touserdata(state, lua_upvalueindex(2))?.asStableRef<LuaFunction>()!!
    val function = functionPtr.get()
    self.stateOutputArgs.reset()
    try {
        function.call(self.stateInputArgs, self.stateOutputArgs)
        self.stateOutputArgs.pushed
    } catch (e: Throwable) {
        luaL_error(state, e.message ?: "")
        0
    }
}