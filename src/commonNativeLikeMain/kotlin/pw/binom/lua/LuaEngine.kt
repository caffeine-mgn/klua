package pw.binom.lua

actual class LuaEngine actual constructor() {
    init {
        StdOut.info("LuaEngine #1")
    }

    internal val ll = LuaStateAndLib(
        LUALIB_INSTANCE.luaL_newstate1() ?: throw RuntimeException("Can't create Lua State"),
        LUALIB_INSTANCE
    )

    init {
        StdOut.info("LuaEngine #2")
    }

    init {
        ll.lib.luaL_openlibs1(ll.state)
    }

    init {
        StdOut.info("LuaEngine #3")
    }

    private var closureGcRef = makeRef(LuaValue.FunctionValue(closureGc, upvalues = emptyList()))

    init {
        StdOut.info("LuaEngine #4")
    }

    private var userdataGcRef = makeRef(LuaValue.FunctionValue(userdataGc, upvalues = emptyList()))

    init {
        StdOut.info("LuaEngine #5")
    }

    private var internalPinned = HashMap<LuaValue.Ref, Int>()
    actual val pinned: Set<LuaValue.Ref>
        get() = internalPinned.keys

    private val cleaner = createCleaner1(ll.state) {
        println("LuaEngine disposing")
        ll.lib.lua_close1(it)
    }

    actual operator fun get(name: String): LuaValue {
        ll.checkState {
            printStack("before-get-global")
            ll.lib.lua_getglobal1(ll.state, name)
            printStack("after-get-global")
            val value = ll.readValue(-1, true)
            ll.pop(1)
            return value
        }
    }

    actual operator fun set(name: String, value: LuaValue) {
        ll.checkState {
            ll.state.printStack("before push")
            ll.pushValue(value)
            ll.state.printStack("after push")
            ll.lib.lua_setglobal1(ll.state, name)
            ll.state.printStack("after set")
        }
    }

    actual fun eval(text: String): List<pw.binom.lua.LuaValue> {
        val r = ll.lib.luaL_loadstring1(ll.state, text)
        when (r) {
            0 -> {}
            LUA_ERRSYNTAX1 -> {
                val msg = ll.lib.lua_tostring1(ll.state, -1)
                ll.pop(1)
                throw LuaException(msg ?: "Compile error")
            }
            LUA_ERRMEM1 -> throw LuaException("LUA_ERRMEM")
            else -> throw LuaException("Can't eval text \"$text\"")
        }
        val exitCode = ll.lib.lua_pcall1(ll.state, 0, LUA_MULTRET1, 0)
        return pcallProcessing(ll, exitCode)
    }

    actual fun call(
        functionName: String,
        vararg args: LuaValue
    ): List<LuaValue> {
        ll.lib.lua_getglobal1(ll.state, functionName)
        if (ll.lib.lua_isnil1(ll.state, -1)) {
            throw LuaException("Function \"$functionName\" not found")
        }
        if (!ll.lib.lua_isfunction1(ll.state, -1)) {
            ll.pop(1)
            throw LuaException("\"$functionName\" is not a function")
        }
        args.forEach {
            ll.pushValue(it)
        }
        val exec = ll.lib.lua_pcall1(ll.state, args.size, LUA_MULTRET1, 0)
        return pcallProcessing(ll, exec)
    }

    actual fun call(
        value: LuaValue,
        vararg args: LuaValue
    ): List<LuaValue> {
        StdOut.info("Try call $value, args:${args.toList()}")
        ll.pushValue(value)
        args.forEach {
            ll.pushValue(it)
        }
        val exec = ll.lib.lua_pcall1(ll.state, args.size, LUA_MULTRET1, 0)
        return pcallProcessing(ll, exec)
    }

    actual fun makeRef(value: LuaValue.FunctionValue): LuaValue.FunctionRef {
        ll.checkState {
            try {
                println("LuaEngine-makeRef #1   ${value}")
                ll.pushValue(value)
                println("LuaEngine-makeRef #2  ll.state=${ll.state}")
                println("top=${ll.lib.lua_gettop1(ll.state)}")
                printStack("After put current function")
                val ptr = ll.lib.lua_topointer1(ll.state, -1)!!
                println("LuaEngine-makeRef #3")
                val ref = ll.makeRef(popValue = true)
                println("LuaEngine-makeRef #4")
                return LuaValue.FunctionRef(ref = ref, ptr = ptr, ll = ll)
            } catch (e: Throwable) {
                e.printStackTrace()
                throw e
            }
        }
    }

    actual fun makeRef(value: LuaValue.TableValue): LuaValue.TableRef {
        ll.checkState {
            ll.pushValue(value)
            val ptr = ll.lib.lua_topointer1(ll.state, -1)!!
            val ref = ll.makeRef(popValue = true)
            return LuaValue.TableRef(ref = ref, ptr = ptr, ll = ll)
        }
    }

    actual fun pin(ref: LuaValue.Ref): Boolean {
        if (ref in internalPinned) {
            return false
        }
        internalPinned[ref] = ll.lib.luaL_ref1(ll.state, LUA_REGISTRYINDEX1)
        return true
    }

    actual fun unpin(ref: LuaValue.Ref): Boolean {
        val refId = internalPinned.remove(ref) ?: return false
        ll.lib.luaL_unref1(ll.state, LUA_REGISTRYINDEX1, refId)
        return true
    }

    actual fun freeAllPinned() {
        internalPinned.forEach {
            ll.lib.luaL_unref1(ll.state, LUA_REGISTRYINDEX1, it.value)
        }
        internalPinned.clear()
    }

    actual fun createUserData(value: LuaValue.LightUserData): LuaValue.UserData {
        ll.checkState {
            val mem = ll.lib.lua_newuserdata1(ll.state, Heap.PTR_SIZE)!!
            ll.lib.heap.setPtrFromPtr(mem, value = value.lightPtr)
            val ret = LuaValue.UserData(ll.makeRef(), ll)
            return ret
        }
    }

    actual fun createACClosure(func: LuaFunction): LuaValue.UserData {
        val ref = StableRef1.create(func)
        val luaFunc = LuaValue.FunctionValue(CLOSURE_FUNCTION, listOf(LuaValue.LightUserData(ref.asCPointer())))
        val metatable = LuaValue.TableValue(
            "__call".lua to luaFunc,
            "__gc".lua to closureGcRef
        )
        val userData = createUserData(LuaValue.LightUserData(AC_CLOSURE_PTR))
        userData.metatable = metatable
        return userData
    }

    actual fun setAC(userdata: LuaValue.UserData) {
        val table = userdata.metatable
        if (table is LuaValue.Table) {
            table["__gc".lua] = userdataGcRef
        } else {
            userdata.metatable = LuaValue.TableValue("__gc".lua to userdataGcRef)
        }
    }

    actual fun createAC(value: LuaValue.LightUserData): LuaValue.UserData {
        val ud = createUserData(value)
        setAC(ud)
        return ud
    }

    actual fun createAC(value: Any?): LuaValue.UserData {
        try {
            val ref = value?.let { StableRef1.create(it) }?.asCPointer()
            return createAC(LuaValue.LightUserData(ref))
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
    }
}

internal fun LuaStateAndLib.callClosure(vararg args: LuaValue): List<LuaValue> {
    args.forEach {
        pushValue(it)
    }
    val exec = lib.lua_pcall1(state, args.size, LUA_MULTRET1, 0)
    return pcallProcessing(this, exec)
}

private fun pcallProcessing(luaLib: LuaStateAndLib, exeCode: Int): List<LuaValue> {
    when (exeCode) {
        LUA_OK1 -> {
            val count = luaLib.lib.lua_gettop1(luaLib.state)
            val list = (1..count).map {
                luaLib.readValue(it, true)
            }
            luaLib.pop(count)
            return list
        }
        LUA_ERRRUN1 -> {
            val message =
                if (luaLib.lib.lua_gettop1(luaLib.state) == 1 && luaLib.lib.lua_isstring1(luaLib.state, 1) != 0) {
                    val str = luaLib.lib.lua_tostring1(luaLib.state, 1)
                    luaLib.pop(1)
                    str
                } else {
                    null
                }
            luaLib.lib.luaL_traceback1(luaLib.state, luaLib.state, message, 1)
            val fullMessage = luaLib.lib.lua_tostring1(luaLib.state, -1)
            luaLib.pop(1)
            throw LuaException(fullMessage)
        }
        LUA_ERRMEM1 -> throw RuntimeException("memory allocation error. For such errors, Lua does not call the message handler.")
        LUA_ERRERR1 -> throw RuntimeException("error while running the message handler.")
        else -> throw RuntimeException("Unknown invoke status")
    }
}