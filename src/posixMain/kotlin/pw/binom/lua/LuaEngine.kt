package pw.binom.lua

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.internal_lua.*

@OptIn(ExperimentalForeignApi::class)
actual class LuaEngine actual constructor(
    libraries: Set<LuaLibrary>,
    limits: LuaLimits,
    allowBinaryChunks: Boolean,
) : AutoCloseable {

    internal val ll = LuaContext(limits)

    private val allowBinaryChunks: Boolean = allowBinaryChunks
    private val limits: LuaLimits = limits

    actual val closureAutoGcFunction: LuaValue.FunctionRef =
        makeRef(LuaValue.FunctionValue(userdataGc))
    actual val userdataAutoGcFunction: LuaValue.FunctionRef =
        makeRef(LuaValue.FunctionValue(userdataGc))

    init {
        openLibs(libraries)
    }

    /**
     * Prepares the native limit control block for a top-level operation: resets
     * the per-call instruction counter, cancellation flag and last reason, and
     * arms the wall-clock deadline.
     */
    private fun prepareLimits() {
        ll.resetLimits()
        val timeout = limits.timeout
        if (timeout != null) {
            ll.setLimitTimeout(timeout.inWholeMicroseconds)
        }
    }

    /**
     * Throws [LuaLimitException] if the last native operation aborted because a
     * limit (or cancellation) tripped. No-op otherwise.
     */
    private fun throwIfLimited() {
        val kind = LuaLimitReason.kindOf(ll.lastLimitReason()) ?: return
        throw LuaLimitException(kind, LuaLimitReason.message(kind))
    }

    actual fun cancel() {
        ll.setLimitCancel(true)
    }

    actual override fun close() {
        // Releases the Lua state and the limit control block exactly once;
        // the Cleaner that backs the LuaContext wrapper remains as a safety
        // net for callers who forget to close().
        ll.close()
    }

    actual operator fun get(name: String): LuaValue {
        ll.state.checkState {
            lua_getglobal(ll.state, name)
            val value = ll.readValue(-1, true)
            lua_pop(ll.state, 1)
            return value
        }
    }

    actual operator fun set(name: String, value: LuaValue) {
        ll.state.checkState {
            ll.pushValue(value)
            lua_setglobal(ll.state, name)
        }
    }

    actual fun eval(text: String): List<pw.binom.lua.LuaValue> {
        prepareLimits()
        // We can't call `luaL_loadbufferx` directly from Kotlin/Native because
        // its `size_t sz` parameter maps to `ULong` on 64-bit K/N targets and
        // to `UInt` on 32-bit K/N targets, with no portable Kotlin type. The
        // common Kotlin metadata compilation picks one (UInt, the smaller)
        // and that breaks the call site on 64-bit targets. Instead we use
        // `klua_loadstring` from klua_limits.h, a thin wrapper that takes a
        // NUL-terminated C string and computes the byte length via strlen
        // internally — so the Kotlin side never has to plumb a size_t.
        val r = klua_loadstring(
            ll.state,
            text,
            if (allowBinaryChunks) null else "t",
        )
        when (r) {
            0 -> {}
            LUA_ERRSYNTAX -> {
                val msg = lua_tostring(ll.state, -1)
                lua_pop(ll.state, 1)
                throw LuaException(msg ?: "Compile error")
            }

            LUA_ERRMEM -> {
                throwIfLimited()
                throw LuaException("LUA_ERRMEM")
            }

            else -> throw LuaException("Can't eval text \"$text\"")
        }
        val exitCode = lua_pcall1(ll.state, 0, LUA_MULTRET, 0)
        // pcallProcessing pops the results (or the error value) before it
        // throws, so the stack is clean when the limit reason is checked. A
        // limit that trips inside a Lua `pcall` is caught by it, but the
        // control block's reason stays set — hence the unconditional check.
        val result = try {
            pcallProcessing(ll, exitCode)
        } catch (e: Throwable) {
            throwIfLimited()
            throw e
        }
        throwIfLimited()
        return result
    }

    actual fun call(
        functionName: String,
        vararg args: LuaValue,
    ): List<LuaValue> {
        prepareLimits()
        lua_getglobal(ll.state, functionName)
        if (lua_isnil1(ll.state, -1)) {
            // Pop the nil pushed by lua_getglobal before throwing; the JVM
            // counterpart pops in both branches and the POSIX side already
            // pops in the not-a-function branch. Without this pop the call
            // accumulates one stack slot per failed lookup.
            lua_pop(ll.state, 1)
            throw LuaException("Function \"$functionName\" not found")
        }
        if (!lua_isfunction1(ll.state, -1)) {
            lua_pop(ll.state, 1)
            throw LuaException("\"$functionName\" is not a function")
        }
        args.forEach {
            ll.pushValue(it)
        }
        val exec = lua_pcall1(ll.state, args.size, LUA_MULTRET, 0)
        val result = try {
            pcallProcessing(ll, exec)
        } catch (e: Throwable) {
            throwIfLimited()
            throw e
        }
        throwIfLimited()
        return result
    }

    actual fun call(
        value: LuaValue,
        vararg args: LuaValue,
    ): List<LuaValue> {
        prepareLimits()
        val topBefore = lua_gettop(ll.state)
        ll.pushValue(value)
        args.forEach {
            ll.pushValue(it)
        }
        val exec = lua_pcall1(ll.state, args.size, LUA_MULTRET, 0)
        val result = try {
            pcallProcessing(ll, exec, topBefore)
        } catch (e: Throwable) {
            throwIfLimited()
            throw e
        }
        throwIfLimited()
        return result
    }

    /**
     * POSIX implementation of [openLibs]. Loads only the selected [libraries]
     * via [LuaContext.openLibs] (per-library `luaL_requiref`), mirroring the
     * JVM `openLibsMask` path.
     */
    actual fun openLibs(libraries: Set<LuaLibrary>) {
        ll.openLibs(libraries)
        if (!allowBinaryChunks && LuaLibrary.BASE in libraries) {
            installBinaryChunkGuard()
        }
    }

    /**
     * POSIX implementation of [openStandardLibs]. Delegates to
     * [LuaContext.openStandardLibs] which invokes [luaL_openlibs] to load
     * every standard library into the engine's state.
     */
    actual fun openStandardLibs() {
        ll.openStandardLibs()
        if (!allowBinaryChunks) {
            installBinaryChunkGuard()
        }
    }

    /**
     * Installs [BINARY_CHUNK_GUARD_SCRIPT] so that Lua's own `load`/`loadfile`
     * cannot accept precompiled bytecode when [allowBinaryChunks] is `false`.
     */
    private fun installBinaryChunkGuard() {
        eval(BINARY_CHUNK_GUARD_SCRIPT)
    }

    actual fun dumpFunction(value: LuaValue, strip: Boolean): ByteArray? {
        return ll.state.checkState {
            ll.pushValue(value)
            memScoped {
                val lenVar = alloc<ULongVar>()
                lenVar.value = 0uL
                val p = klua_dumpfunction(ll.state, if (strip) 1 else 0, lenVar.ptr)
                lua_pop(ll.state, 1) // снимаем саму функцию
                if (p == null) {
                    return@checkState null
                }
                val len = lenVar.value.toInt()
                val result = ByteArray(len) { i -> p.get(i).toByte() }
                klua_dump_free(p)
                result
            }
        }
    }

    actual fun loadBytecode(bytes: ByteArray, name: String?): LuaValue.FunctionRef? {
        if (bytes.isEmpty()) return null
        return ll.state.checkState {
            // cinterop мапит `const char*` в Kotlin String, а в байткоде есть NUL,
            // поэтому в C-сигнатуре стоит `const unsigned char*` — здесь указатель.
            bytes.usePinned { pinned ->
                val buf = pinned.addressOf(0).reinterpret<UByteVar>()
                val r = klua_loadbytecode(ll.state, buf, bytes.size.toULong(), name)
                if (r != 0) {
                    lua_pop(ll.state, 1) // сообщение об ошибке
                    return@checkState null
                }
                val ptr = lua_topointer(ll.state, -1)
                    ?: run {
                        lua_pop(ll.state, 1)
                        return@checkState null
                    }
                val ref = ll.state.makeRef(popValue = true)
                LuaValue.FunctionRef(ref = ref, ptr = ptr, ll = ll)
            }
        }
    }

    actual fun makeRef(value: LuaValue.FunctionValue): LuaValue.FunctionRef {
        ll.state.checkState {
            ll.pushValue(value)
            // lua_topointer returns null for values without a GC object
            // (booleans, nil, numbers, lightuserdata); throw a typed
            // exception instead of NPE so callers can react to the
            // "not a Lua object" condition explicitly.
            val ptr = lua_topointer(ll.state, -1)
                ?: throw LuaException("Cannot makeRef of value with no GC pointer")
            val ref = ll.state.makeRef(popValue = true)
            return LuaValue.FunctionRef(ref = ref, ptr = ptr, ll = ll)
        }
    }

    actual fun makeRef(value: LuaValue.TableValue): LuaValue.TableRef {
        ll.state.checkState {
            ll.pushValue(value)
            // See [makeRef(FunctionValue)] — throw a typed exception
            // instead of NPE on values without a GC pointer.
            val ptr = lua_topointer(ll.state, -1)
                ?: throw LuaException("Cannot makeRef of value with no GC pointer")
            val ref = ll.state.makeRef(popValue = true)
            return LuaValue.TableRef(ref = ref, ptr = ptr, ll = ll)
        }
    }

    actual fun createUserData(value: LuaValue.LightUserData): LuaValue.UserData {
        ll.state.checkState {
            val mem = lua_newuserdata1(ll.state, Heap.PTR_SIZE)!!
            Heap.setPtrFromPtr(mem, value = value.lightPtr)
            val ret = LuaValue.UserData(ll.state.makeRef(), ll)
            // Install __gc mirroring JVM createUserData(LightUserData) so
            // the underlying StableRef is disposed when Lua collects the
            // userdata. Previously this branch silently skipped the
            // metatable, leaking every AC userdata's payload.
            ret.metatable = luaTableOf("__gc" to userdataAutoGcFunction)
            return ret
        }
    }

    actual fun createUserData(value: Any): LuaValue.UserData {
        val ptr = StableRef.create(value)
        try {
            val ret = createUserData(LuaValue.LightUserData(ptr.asCPointer()))
            ret.metatable = LuaValue.of(
                mapOf(LuaValue.of("__gc") to closureAutoGcFunction)
            )
            return ret
        } catch (e: Throwable) {
            ptr.dispose()
            throw e
        }
    }

    actual fun createACClosure(func: LuaFunction): LuaValue.UserData {
        // Single source of truth: the userdata's payload holds the only
        // StableRef<LuaFunction>. The metatable's __call installs a Lua
        // closure backed by AC_CLOSURE_FUNCTION (a no-upvalue cfunction)
        // that reads the same ref from the userdata payload at idx 1. When
        // Lua's __gc fires, userdataGc disposes the payload's StableRef and
        // the Lua closure has nothing to dangle — closing the UAF window
        // reported after the d90a8f8 JVM-side fix.
        val ref = StableRef.create(func)
        val luaFunc = LuaValue.FunctionValue(
            ptr = AC_CLOSURE_FUNCTION,
            upValues = emptyList(),
        )
        val userData = createUserData(LuaValue.LightUserData(ref.asCPointer()))
        // createUserData already installed a default __gc metatable mapping
        // to userdataAutoGcFunction (which on POSIX aliases userdataGc);
        // augment it with __call but DO NOT overwrite __gc.
        val existing = userData.metatable
        val merged = when (existing) {
            is LuaValue.TableValue -> existing.map.toMutableMap()
            is LuaValue.Table -> existing.toMap().toMutableMap()
            else -> mutableMapOf<LuaValue, LuaValue>()
        }
        merged[LuaValue.of("__call")] = luaFunc
        userData.metatable = LuaValue.TableValue(merged)
        return userData
    }

    actual fun setAC(userdata: LuaValue.UserData) {
        val table = userdata.metatable
        if (table is LuaValue.Table) {
            table["__gc".lua] = closureAutoGcFunction
        } else {
            userdata.metatable = luaTableOf("__gc" to closureAutoGcFunction)
        }
    }

    actual fun createAC(value: LuaValue.LightUserData): LuaValue.UserData {
        val ud = createUserData(value)
        setAC(ud)
        return ud
    }

    actual fun createAC(value: Any?): LuaValue.UserData {
        // Capture the StableRef so the catch path can dispose it on
        // failure — without this, a throw from createAC(LightUserData(...))
        // (e.g. on Lua OOM) leaks the ref and pins the Kotlin object for
        // the lifetime of the engine. Also drop the printStackTrace spam;
        // the exception propagates unchanged.
        if (value == null) {
            return createAC(LuaValue.LightUserData(null))
        }
        val ref = StableRef.create(value)
        return try {
            createAC(LuaValue.LightUserData(ref.asCPointer()))
        } catch (t: Throwable) {
            ref.dispose()
            throw t
        }
    }
}

/**
 * Call a function that is ALREADY on top of the Lua stack.
 *
 * [topBefore] — высота стека ДО push функции. Считать результаты как
 * `lua_gettop()` можно только при пустом стеке: `lua_pcall` заменяет
 * функцию+аргументы результатами на месте, поэтому первый результат иначе
 * съедается, а лишнее остаётся на стеке (симметрично багу в JVM-части).
 */
@OptIn(ExperimentalForeignApi::class)
internal fun LuaContext.callClosure(vararg args: LuaValue): List<LuaValue> {
    val topBefore = lua_gettop(state)
    return callClosureAt(topBefore, *args)
}

/**
 * Вызов функции, УЖЕ лежащей на вершине стека.
 *
 * [topBefore] — высота стека ДО того, как функция была положена. Считать
 * результаты от `lua_gettop()` можно только при пустом стеке: `lua_pcall`
 * заменяет функцию+аргументы результатами на месте, поэтому иначе первый
 * результат съедается, а лишнее остаётся на стеке.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun LuaContext.callClosureAt(topBefore: Int, vararg args: LuaValue): List<LuaValue> {
    args.forEach {
        pushValue(it)
    }
    val exec = lua_pcall1(state, args.size, LUA_MULTRET, 0)
    return pcallProcessing(this, exec, topBefore)
}

@OptIn(ExperimentalForeignApi::class)
private fun pcallProcessing(luaLib: LuaContext, exeCode: Int, topBefore: Int = 0): List<LuaValue> {
    when (exeCode) {
        LUA_OK -> {
            val count = lua_gettop(luaLib.state) - topBefore
            if (count <= 0) return emptyList()
            val list = (1..count).map {
                luaLib.readValue(topBefore + it, true)
            }
            lua_pop(luaLib.state, count)
            return list
        }

        LUA_ERRRUN -> {
            // Multi-value Lua errors can leave several entries on the stack
            // before luaL_traceback is called. Walk all of them into the
            // traceback so the original N-1 error values do not leak across
            // subsequent operations. The single-string fast path pops the
            // lone entry after reading it.
            val topBefore = lua_gettop(luaLib.state)
            val message =
                if (topBefore == 1 && lua_isstring(luaLib.state, 1) != 0) {
                    val str = lua_tostring(luaLib.state, 1)
                    if (topBefore > 0) lua_pop(luaLib.state, topBefore)
                    str
                } else {
                    if (topBefore > 0) lua_pop(luaLib.state, topBefore)
                    null
                }
            luaL_traceback(luaLib.state, luaLib.state, message, 1)
            val fullMessage = lua_tostring(luaLib.state, -1)
            lua_pop(luaLib.state, 1)
            throw LuaException(fullMessage)
        }

        LUA_ERRMEM -> throw RuntimeException("memory allocation error. For such errors, Lua does not call the message handler.")
        LUA_ERRERR -> throw RuntimeException("error while running the message handler.")
        else -> throw RuntimeException("Unknown invoke status")
    }
}
