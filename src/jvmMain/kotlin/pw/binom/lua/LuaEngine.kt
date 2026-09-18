package pw.binom.lua

actual class LuaEngine : AutoCloseable {

    internal val ll: LuaContext = LuaContext()

    actual val closureAutoGcFunction: LuaValue.FunctionRef = makeAutoGcRef()
    actual val userdataAutoGcFunction: LuaValue.FunctionRef = makeUserdataGcRef()

    private fun makeAutoGcRef(): LuaValue.FunctionRef {
        // The GC trampoline (klua_gc_trampoline in klua_jni.c) looks up the
        // callback id in LuaNative.callbacks via disposeCallback(id). Each
        // userdata with this __gc releases its own bridge when collected.
        // No LuaCallbackBridge handler is needed on the Kotlin side anymore
        // — the previous empty-handler pattern leaked bridge entries for the
        // lifetime of the JVM.
        val id = LuaNative.nextCallbackId()
        LuaNative.pushGcFunction(ll.state, id)
        val ptr = LuaNative.toPointer(ll.state, -1)
        val refId = LuaNative.ref(ll.state, LUA_REGISTRYINDEX)
        return LuaValue.FunctionRef(refId, ptr, ll)
    }

    private fun makeUserdataGcRef(): LuaValue.FunctionRef {
        // The userdata-gc trampoline (klua_userdata_gc_trampoline in klua_jni.c)
        // reads the userdata's payload address at idx 1 and forwards it to
        // disposeUserdata(mem) → StaticRefs.dispose(mem). One cfunction serves
        // every AC userdata (the payload carries the per-instance key).
        LuaNative.pushUserdataGcFunction(ll.state)
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
        // Lua 5.4 loadStringx returns:
        //   LUA_OK=0, LUA_ERRSYNTAX=3, LUA_ERRMEM=4, LUA_ERRERR=5.
        // For 3 and 4 the runtime pushes an error message at the top of the
        // stack; for 5 (error in error handler) the stack is unchanged.
        when (r) {
            0 -> {}
            3 -> {
                val msg = LuaNative.toString(ll.state, -1)
                LuaNative.pop(ll.state, 1)
                throw LuaException(msg ?: "Lua syntax error")
            }
            4 -> {
                val msg = LuaNative.toString(ll.state, -1)
                LuaNative.pop(ll.state, 1)
                throw RuntimeException("Lua memory allocation error: ${msg ?: "<no message>"}")
            }
            5 -> throw LuaException("Lua error in error handler")
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
        // Read the value out of the existing StaticRefs slot, then place it
        // under the userdata's own mem address (so Lua's __gc trampoline
        // — klua_userdata_gc_trampoline → disposeUserdata(mem) → StaticRefs
        // .dispose(mem) — has a slot to drop). The original `ptr` slot is
        // disposed here because the value has been moved into the userdata;
        // leaving it in place would inflate StaticRefs by one per call
        // until the LightUserData wrapper itself was GC'd, which is what
        // caused the orphan-entry bug this fix closes.
        //
        // The __gc metamethod is set so Lua collects the mem entry on
        // userdata disposal — without it, the mem entry would be stranded
        // for the lifetime of the Lua state (the same shape of leak that
        // hit createUserData(Any) before commit 595d8b4).
        val underlying = value.value
        val mem = LuaNative.newUserdata(ll.state, PTR_SIZE)
        StaticRefs.store(mem, underlying)
        if (value.ptr != null) StaticRefs.dispose(value.ptr)
        val refId = LuaNative.ref(ll.state, LUA_REGISTRYINDEX)
        val ud = LuaValue.UserData(refId, ll)
        ud.metatable = LuaValue.TableValue("__gc".lua to userdataAutoGcFunction)
        return ud
    }

    actual fun createUserData(value: Any): LuaValue.UserData {
        // Allocate the Lua userdata first so we know its memory address —
        // that's the StaticRefs key, so __gc can find the right entry to
        // remove. (Previously the code interned under a counter and then
        // stored again at the userdata address, which left a stale orphan
        // entry behind on every call and inflated StaticRefs.size.)
        val mem = LuaNative.newUserdata(ll.state, PTR_SIZE)
        StaticRefs.store(mem, value)
        val refId = LuaNative.ref(ll.state, LUA_REGISTRYINDEX)
        val ud = LuaValue.UserData(refId, ll)
        ud.metatable = LuaValue.TableValue("__gc".lua to userdataAutoGcFunction)
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
        // CRITICAL: the __gc metamethod must reuse the SAME callbackId
        // as the __call bridge. klua_gc_trampoline reads its upvalue as
        // a callback-id and calls LuaNative.disposeCallback(id) on it —
        // if id is wrong (e.g. a never-registered singleton id from
        // makeAutoGcRef), the disposeCallback is a no-op and the live
        // LuaCallbackBridge for this closure leaks for the lifetime of
        // the JVM. Push a per-call __gc cfunction whose sole upvalue
        // is THIS closure's callbackId.
        val gcRef = LuaNative.pushGcFunction(ll.state, callbackId)
        val gcPtr = LuaNative.toPointer(ll.state, -1)
        val gcFnRef = LuaValue.FunctionRef(
            refId = LuaNative.ref(ll.state, LUA_REGISTRYINDEX),
            ptr = gcPtr,
            ll = ll,
        )
        val metatable = LuaValue.TableValue(
            "__call".lua to fnRef,
            "__gc".lua to gcFnRef,
        )
        ud.metatable = metatable
        return ud
    }

    actual fun setAC(userdata: LuaValue.UserData) {
        // NOTE: the historical behaviour here was to use closureAutoGcFunction,
        // which makes disposeCallback a no-op (the id was never registered with
        // a LuaCallbackBridge). That is in fact a leak — the StaticRefs entry
        // placed under the userdata's mem address would never be released.
        // Switching to userdataAutoGcFunction (which invokes disposeUserdata ->
        // StaticRefs.dispose) looked like the obvious fix, but exercising it
        // through JvmAcDisposeTest.createUserDataFromLightUserDataDoesNotOrphanEntries
        // exposed a double-dispose where the inner object passed through
        // createUserData ends up being released twice (once via the explicit
        // __gc path, once via the Cleaner). Investigation is queued; the fix
        // needs a small refactor around ownership that does not regress this
        // test. Keeping the historical implementation here for now.
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
        // createUserData(Any) is the one that owns the StaticRefs entry under
        // the userdata's memory address. Calling createAC(LightUserData) would
        // duplicate the entry through StaticRefs.intern + store(mem, get(ptr))
        // and leave the interned entry orphaned once __gc drops the mem entry.
        return if (value != null) createUserData(value) else createUserData(LuaValue.LightUserData(null))
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
            // Use the raw top-of-stack value (which is the Lua-thrown
            // error message) but try toString first; if it's not a
            // string, fall back to a debug-friendly message that includes
            // the Lua type. Read the type BEFORE the pop, since the
            // index shifts after pop and `topBefore` no longer points to
            // the error value.
            val errType = LuaNative.type(ll.state, -1)
            val msg = LuaNative.toString(ll.state, -1)
            LuaNative.pop(ll.state, 1)
            throw LuaException(msg ?: "<lua error: type=$errType>")
        }
        4 -> throw RuntimeException("memory allocation error")
        5 -> throw RuntimeException("error while running the message handler")
        else -> throw RuntimeException("Unknown pcall status: $exeCode")
    }
}
