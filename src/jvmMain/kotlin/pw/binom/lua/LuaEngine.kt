package pw.binom.lua

actual class LuaEngine : AutoCloseable {

    internal val ll: LuaContext = LuaContext()

    actual val closureAutoGcFunction: LuaValue.FunctionRef = makeAutoGcRef { _ -> emptyList<LuaValue>() }
    actual val userdataAutoGcFunction: LuaValue.FunctionRef = makeAutoGcRef { ctx ->
        StaticRefs.dispose(LuaNative.toUserdata(ctx.state, -1))
        emptyList()
    }

    private fun makeAutoGcRef(handler: LuaCallbackBridge): LuaValue.FunctionRef {
        val id = LuaNative.nextCallbackId()
        LuaNative.setCallback(id, handler)
        LuaNative.pushCFunction(ll.state, id)
        val ptr = LuaNative.toPointer(ll.state, -1)
        val refId = LuaNative.ref(ll.state, LUA_REGISTRYINDEX)
        return LuaValue.FunctionRef(refId, ptr, ll)
    }

    actual override fun close() {
        ll.close()
    }

    actual operator fun get(name: String): LuaValue {
        LuaNative.getGlobal(ll.state, name)
        val value = ll.readValue(-1, true)
        LuaNative.pop(ll.state, 1)
        return value
    }

    actual operator fun set(name: String, value: LuaValue) {
        pushValue(ll.state, value)
        LuaNative.setGlobal(ll.state, name)
    }

    actual fun eval(text: String): List<LuaValue> {
        val r = LuaNative.loadString(ll.state, text)
        when (r) {
            0 -> {}
            4 -> {
                val msg = LuaNative.toString(ll.state, -1)
                LuaNative.pop(ll.state, 1)
                throw LuaException(msg ?: "Compile error")
            }
            5 -> throw LuaException("LUA_ERRMEM")
            else -> throw LuaException("Can't eval text \"$text\" (status=$r)")
        }
        val exitCode = LuaNative.pcall(ll.state, 0, -1, 0)
        return pcallProcessing(ll, exitCode)
    }

    actual fun call(functionName: String, vararg args: LuaValue): List<LuaValue> {
        LuaNative.getGlobal(ll.state, functionName)
        if (LuaNative.isNil(ll.state, -1)) {
            LuaNative.pop(ll.state, 1)
            throw LuaException("Function \"$functionName\" not found")
        }
        if (!LuaNative.isFunction(ll.state, -1)) {
            LuaNative.pop(ll.state, 1)
            throw LuaException("\"$functionName\" is not a function")
        }
        args.forEach { pushValue(ll.state, it) }
        val r = LuaNative.pcall(ll.state, args.size, -1, 0)
        return pcallProcessing(ll, r)
    }

    actual fun call(value: LuaValue, vararg args: LuaValue): List<LuaValue> {
        pushValue(ll.state, value)
        args.forEach { pushValue(ll.state, it) }
        val r = LuaNative.pcall(ll.state, args.size, -1, 0)
        return pcallProcessing(ll, r)
    }

    actual fun makeRef(value: LuaValue.FunctionValue): LuaValue.FunctionRef {
        // Re-push the closure, then capture its pointer and store a stable registry
        // reference in that order — luaL_ref() POPS the value, so getting the pointer
        // afterwards would read the value below the just-pushed one.
        pushValue(ll.state, value)
        val ptr = LuaNative.toPointer(ll.state, -1)
        val refId = LuaNative.ref(ll.state, LUA_REGISTRYINDEX)
        return LuaValue.FunctionRef(refId, ptr, ll)
    }

    actual fun makeRef(value: LuaValue.TableValue): LuaValue.TableRef {
        pushValue(ll.state, value)
        val ptr = LuaNative.toPointer(ll.state, -1)
        val refId = LuaNative.ref(ll.state, LUA_REGISTRYINDEX)
        return LuaValue.TableRef(refId, ptr, ll)
    }

    actual fun createUserData(value: LuaValue.LightUserData): LuaValue.UserData {
        val mem = LuaNative.newUserdata(ll.state, PTR_SIZE)
        StaticRefs.store(mem, value.ptr?.let { StaticRefs.get(it) })
        val refId = LuaNative.ref(ll.state, LUA_REGISTRYINDEX)
        return LuaValue.UserData(refId, ll)
    }

    actual fun createUserData(value: Any): LuaValue.UserData {
        val ptr = StaticRefs.intern(value)
        val mem = LuaNative.newUserdata(ll.state, PTR_SIZE)
        StaticRefs.store(mem, value)
        val refId = LuaNative.ref(ll.state, LUA_REGISTRYINDEX)
        val ud = LuaValue.UserData(refId, ll)
        ud.metatable = LuaValue.TableValue("__gc".lua to closureAutoGcFunction)
        return ud
    }

    actual fun createACClosure(func: LuaFunction): LuaValue.UserData {
        val callbackId = LuaNative.nextCallbackId()
        LuaNative.setCallback(callbackId, LuaCallbackBridge { ctx ->
            // __call metamethod: index 1 is the userdata (self), real args start at 2.
            val top = LuaNative.getTop(ctx.state)
            val args = (2..top).map { ctx.readValue(it, true) }
            LuaNative.pop(ctx.state, top)
            func.call(args)
        })
        // Push the cclosure, capture the pointer at -1, *then* ref() which pops it.
        LuaNative.pushCFunction(ll.state, callbackId)
        val ptr = LuaNative.toPointer(ll.state, -1)
        val fnRef = LuaValue.FunctionRef(
            refId = LuaNative.ref(ll.state, LUA_REGISTRYINDEX),
            ptr = ptr,
            ll = ll,
        )
        val ud = createUserData(LuaValue.LightUserData(null))
        val metatable = LuaValue.TableValue(
            "__call".lua to fnRef,
            "__gc".lua to closureAutoGcFunction,
        )
        ud.metatable = metatable
        return ud
    }

    actual fun setAC(userdata: LuaValue.UserData) {
        val table = userdata.metatable
        if (table is LuaValue.Table) {
            table["__gc".lua] = closureAutoGcFunction
        } else {
            userdata.metatable = LuaValue.TableValue("__gc".lua to closureAutoGcFunction)
        }
    }

    actual fun createAC(value: LuaValue.LightUserData): LuaValue.UserData {
        val ud = createUserData(value)
        setAC(ud)
        return ud
    }

    actual fun createAC(value: Any?): LuaValue.UserData {
        val ptr = value?.let { StaticRefs.intern(it) }
        val ud = createAC(LuaValue.LightUserData(ptr))
        return ud
    }

    companion object {
        private const val PTR_SIZE = 8
    }
}

internal fun pcallProcessing(ll: LuaContext, exeCode: Int): List<LuaValue> {
    return when (exeCode) {
        0 -> {
            val count = LuaNative.getTop(ll.state)
            val list = (1..count).map { ll.readValue(it, true) }
            LuaNative.pop(ll.state, count)
            list
        }
        2 -> {
            val msg = LuaNative.toString(ll.state, -1) ?: "runtime error"
            LuaNative.pop(ll.state, 1)
            throw LuaException(msg)
        }
        4 -> throw RuntimeException("memory allocation error")
        5 -> throw RuntimeException("error while running the message handler")
        else -> throw RuntimeException("Unknown pcall status: $exeCode")
    }
}
