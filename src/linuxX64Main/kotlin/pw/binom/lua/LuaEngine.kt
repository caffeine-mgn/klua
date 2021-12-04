@file:OptIn(ExperimentalStdlibApi::class)

package pw.binom.lua

import kotlinx.cinterop.*
import platform.internal_lua.*
import kotlin.native.internal.createCleaner

//class LuaEngine1 {
//
//    val state: LuaState = luaL_newstate()!!
//
//    init {
//        luaL_openlibs(state)
//    }
//
//    private var closureGcRef = makeRef(LuaValue.FunctionValue(closureGc, upvalues = emptyList()))
//    private var userdataGcRef = makeRef(LuaValue.FunctionValue(userdataGc, upvalues = emptyList()))
//
//    private var internalPinned = HashMap<LuaValue.Ref, Int>()
//    actual val pinned: Set<LuaValue.Ref>
//        get() = internalPinned.keys
//
//    private val cleaner = createCleaner(state) {
//        println("LuaEngine disposing")
//        lua_close(it)
//    }
//
//    operator fun get(name: String): LuaValue {
//        lua_getglobal1(state, name)
//        return state.readValue(-1, true)
//    }
//
//    operator fun set(name: String, value: LuaValue) {
//        state.pushValue(value)
//        lua_setglobal(state, name)
//    }
//
//    actual fun eval(text: String): List<pw.binom.lua.LuaValue> {
//        val r = luaL_loadstring(state, text)
//        when (r) {
//            0 -> {}
//            LUA_ERRSYNTAX -> {
//                val msg = lua_tostring(state, -1)
//                lua_pop1(state, 1)
//                throw LuaException(msg ?: "Compile error")
//            }
//            LUA_ERRMEM -> throw LuaException("LUA_ERRMEM")
//            else -> throw LuaException("Can't eval text \"$text\"")
//        }
//        val exitCode = lua_pcall(state, 0, LUA_MULTRET, 0)
//        return pcallProcessing(state, exitCode)
//    }
//
//    actual fun call(
//        functionName: String,
//        vararg args: LuaValue
//    ): List<LuaValue> {
//        lua_getglobal(state, functionName)
//        if (lua_isnil(state, -1)) {
//            throw LuaException("Function \"$functionName\" not found")
//        }
//        if (!lua_isfunction(state, -1)) {
//            lua_pop1(state, 1)
//            throw LuaException("\"$functionName\" is not a function")
//        }
//        args.forEach {
//            state.pushValue(it)
//        }
//        val exec = lua_pcall1(state, args.size, LUA_MULTRET, 0)
//        return pcallProcessing(state, exec)
//    }
//
//    actual fun call(
//        value: LuaValue,
//        vararg args: LuaValue
//    ): List<LuaValue> {
//        state.pushValue(value)
//        args.forEach {
//            state.pushValue(it)
//        }
//        val exec = lua_pcall(state, args.size, LUA_MULTRET, 0)
//        return pcallProcessing(state, exec)
//    }
//
//    actual fun makeRef(value: LuaValue.FunctionValue): LuaValue.FunctionRef {
//        state.pushValue(value)
//        val ptr = lua_topointer(state, -1)!!
//        val ref = state.makeRef(popValue = true)
//        return LuaValue.FunctionRef(ref = ref, ptr = ptr, state = state)
//    }
//
//    actual fun makeRef(value: LuaValue.TableValue): LuaValue.TableRef {
//        state.pushValue(value)
//        val ptr = lua_topointer(state, -1)!!
//        val ref = state.makeRef(popValue = true)
//        return LuaValue.TableRef(ref = ref, ptr = ptr, state = state)
//    }
//
//    actual fun pin(ref: LuaValue.Ref): Boolean {
//        if (ref in internalPinned) {
//            return false
//        }
//        internalPinned[ref] = luaL_ref(state, LUA_REGISTRYINDEX)
//        return true
//    }
//
//    actual fun unpin(ref: LuaValue.Ref): Boolean {
//        val refId = internalPinned.remove(ref) ?: return false
//        luaL_unref(state, LUA_REGISTRYINDEX, refId)
//        return true
//    }
//
//    actual fun freeAllPinned() {
//        internalPinned.forEach {
//            luaL_unref(state, LUA_REGISTRYINDEX, it.value)
//        }
//        internalPinned.clear()
//    }
//
//    actual fun createUserData(value: LuaValue.LightUserData): LuaValue.UserData {
//        state.checkState {
//            val mem = lua_newuserdata(state, sizeOf<klua_pointer>().convert())!!
//            val c = mem.reinterpret<klua_pointer>()
//            c.pointed.pointer = value.lightPtr
//            val ret = LuaValue.UserData(state.makeRef(), state)
//            return ret
//        }
//    }
//
//    actual fun createACClosure(func: LuaFunction): LuaValue.UserData {
//        val ref = StableRef.create(func)
//        val luaFunc = LuaValue.FunctionValue(CLOSURE_FUNCTION, listOf(LuaValue.LightUserData(ref.asCPointer())))
//        val metatable = LuaValue.TableValue(
//            "__call".lua to luaFunc,
//            "__gc".lua to closureGcRef
//        )
//        val userData = createUserData(LuaValue.LightUserData(AC_CLOSURE_PTR))
//        userData.metatable = metatable
//        return userData
//    }
//
//    actual fun setAC(userdata: LuaValue.UserData) {
//        val table = userdata.metatable
//        if (table is LuaValue.Table) {
//            table["__gc".lua] = userdataGcRef
//        } else {
//            userdata.metatable = LuaValue.TableValue("__gc".lua to userdataGcRef)
//        }
//    }
//
//    actual fun createAC(value: LuaValue.LightUserData): LuaValue.UserData {
//        val ud = createUserData(value)
//        setAC(ud)
//        return ud
//    }
//
//    actual fun createAC(value: Any?): LuaValue.UserData {
//        try {
//            val ref = value?.let { StableRef.create(it) }?.asCPointer()
//            return createAC(LuaValue.LightUserData(ref))
//        } catch (e: Throwable) {
//            e.printStackTrace()
//            throw e
//        }
//    }
//}
//
//fun LuaState.callClosure(vararg args: LuaValue): List<LuaValue> {
//    args.forEach {
//        pushValue(it)
//    }
//    val exec = lua_pcall(this, args.size, LUA_MULTRET, 0)
//    return pcallProcessing(this, exec)
//}
//
//private fun pcallProcessing(state: LuaState, exeCode: Int): List<LuaValue> {
//    when (exeCode) {
//        LUA_OK -> {
//            val count = lua_gettop(state)
//            val list = (1..count).map {
//                state.readValue(it, true)
//            }
//            lua_pop1(state, count)
//            return list
//        }
//        LUA_ERRRUN -> {
//            val message = if (lua_gettop(state) == 1 && lua_isstring(state, 1) != 0) {
//                val str = lua_tostring(state, 1)
//                lua_pop1(state, 1)
//                str
//            } else {
//                null
//            }
//            luaL_traceback(state, state, message, 1)
//            val fullMessage = lua_tostring(state, -1)
//            lua_pop1(state, 1)
//            throw LuaException(fullMessage)
//        }
//        LUA_ERRMEM -> throw RuntimeException("memory allocation error. For such errors, Lua does not call the message handler.")
//        LUA_ERRERR -> throw RuntimeException("error while running the message handler.")
//        else -> throw RuntimeException("Unknown invoke status")
//    }
//}