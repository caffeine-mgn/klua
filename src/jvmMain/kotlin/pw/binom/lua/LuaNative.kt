package pw.binom.lua

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal object LuaNative {

    init {
        NativeLoader.load()
        init()
    }

    private val nextCallbackId = AtomicInteger(1)

    external fun init()
    external fun newState(): Long
    external fun close(state: Long)

    external fun getTop(state: Long): Int
    external fun setTop(state: Long, top: Int)
    external fun registrySize(state: Long): Int
    external fun pop(state: Long, n: Int)
    external fun pushValue(state: Long, idx: Int)
    external fun remove(state: Long, idx: Int)
    external fun insert(state: Long, idx: Int)
    external fun replace(state: Long, idx: Int)
    external fun rotate(state: Long, idx: Int, n: Int)
    external fun absIndex(state: Long, idx: Int): Int

    external fun pushNil(state: Long)
    external fun pushBoolean(state: Long, v: Boolean)
    external fun pushInteger(state: Long, v: Long)
    external fun pushNumber(state: Long, v: Double)
    external fun pushString(state: Long, s: String?)
    external fun pushLightUserdata(state: Long, ptr: Long)
    external fun pushCClosure(state: Long, callbackId: Int, n: Int)
    external fun pushCFunction(state: Long, callbackId: Int)
    external fun pushGcFunction(state: Long, callbackId: Int)
    external fun pushUserdataGcFunction(state: Long)
    external fun reserveCallbackId(state: Long): Int

    external fun toBoolean(state: Long, idx: Int): Boolean
    external fun toInteger(state: Long, idx: Int): Long
    external fun isInteger(state: Long, idx: Int): Boolean
    external fun toNumber(state: Long, idx: Int): Double
    external fun isNumber(state: Long, idx: Int): Boolean
    external fun toString(state: Long, idx: Int): String?
    external fun toLString(state: Long, idx: Int): String?
    external fun toUserdata(state: Long, idx: Int): Long
    external fun toPointer(state: Long, idx: Int): Long

    external fun type(state: Long, idx: Int): Int
    external fun typename(state: Long, idx: Int): String
    external fun isNil(state: Long, idx: Int): Boolean
    external fun isNone(state: Long, idx: Int): Boolean
    external fun isNoneOrNil(state: Long, idx: Int): Boolean
    external fun isBoolean(state: Long, idx: Int): Boolean
    external fun isString(state: Long, idx: Int): Boolean
    external fun isTable(state: Long, idx: Int): Boolean
    external fun isFunction(state: Long, idx: Int): Boolean
    external fun isCFunction(state: Long, idx: Int): Boolean
    external fun isUserdata(state: Long, idx: Int): Boolean
    external fun isLightUserdata(state: Long, idx: Int): Boolean

    external fun getGlobal(state: Long, name: String)
    external fun setGlobal(state: Long, name: String)

    external fun loadString(state: Long, s: String): Int
    external fun pcall(state: Long, nargs: Int, nresults: Int, errfunc: Int): Int
    external fun traceback(state: Long, msg: String?, level: Int)

    external fun createTable(state: Long, narr: Int, nrec: Int)
    external fun newTable(state: Long)
    external fun setTable(state: Long, idx: Int)
    external fun getTable(state: Long, idx: Int)
    external fun rawSet(state: Long, idx: Int)
    external fun rawGet(state: Long, idx: Int)
    external fun rawSetI(state: Long, idx: Int, n: Long)
    external fun rawGetI(state: Long, idx: Int, n: Long)
    external fun next(state: Long, idx: Int): Int
    external fun setMetatable(state: Long, idx: Int): Int
    external fun getMetatable(state: Long, idx: Int): Int
    external fun len(state: Long, idx: Int)
    external fun rawLen(state: Long, idx: Int): Long

    external fun ref(state: Long, t: Int): Int
    external fun unref(state: Long, t: Int, ref: Int)

    external fun newUserdata(state: Long, sz: Int): Long
    external fun userdataPtr(state: Long, idx: Int): Long
    external fun setIUservalue(state: Long, idx: Int, v: Long)
    external fun getIUservalue(state: Long, idx: Int): Long

    external fun getUpvalue(state: Long, funcIdx: Int, upvalueIdx: Int): String?
    external fun error(state: Long, msg: String): Int

    private val callbacks = ConcurrentHashMap<Int, LuaCallbackBridge>()

    fun nextCallbackId(): Int = nextCallbackId.getAndIncrement()

    fun setCallback(id: Int, bridge: LuaCallbackBridge) {
        callbacks[id] = bridge
    }

    fun unregisterCallback(id: Int) {
        callbacks.remove(id)
    }

    fun callback(id: Int): LuaCallbackBridge? = callbacks[id]

    /** JVM-test hook: number of currently-registered bridge entries. */
    internal val callbackCount: Int
        get() = callbacks.size

    @JvmStatic
    fun invokeCallback(id: Int, statePtr: Long): Long {
        val cb = callbacks[id] ?: return 0L
        return try {
            // The Lua-Native side bridges the raw `lua_State*` pointer back into
            // a Kotlin LuaContext for callback dispatch. Since this happens
            // inside a single native call, we can wrap the pointer in a
            // minimal LuaContext without going through the full constructor
            // (which would allocate a new Lua state — there's nothing to manage
            // here, the state is owned by the engine that registered the bridge).
            val ctx = LuaContext.wrap(statePtr)
            val results = cb.invoke(ctx)
            val nresults = results.size
            results.forEach { ctx.push(it) }
            nresults.toLong()
        } catch (e: Throwable) {
            lastError = e
            (1L shl 31) or 0
        }
    }

    /**
     * Invoked from the C-side [klua_gc_trampoline] (see klua_jni.c) when a
     * userdata that owns a callback id is collected. Removes the bridge from
     * the registry so the JVM-side Kotlin object becomes eligible for GC.
     */
    @JvmStatic
    fun disposeCallback(id: Int) {
        callbacks.remove(id)
    }

    /**
     * Invoked from the C-side [klua_userdata_gc_trampoline] (see klua_jni.c)
     * when a userdata whose payload is the [mem] address is collected.
     * Drops the corresponding [StaticRefs] entry so the Kotlin value becomes
     * eligible for GC.
     */
    @JvmStatic
    fun disposeUserdata(mem: Long) {
        StaticRefs.dispose(mem)
    }

    @Volatile
    private var lastError: Throwable? = null

    @JvmStatic
    fun lastErrorMessage(): String? = lastError?.let { it::class.simpleName + ": " + (it.message ?: "") }
}

internal fun interface LuaCallbackBridge {
    fun invoke(ctx: LuaContext): List<LuaValue>
}

private object LuaNativeType {
    const val NONE = -1
    const val NIL = 0
    const val BOOLEAN = 1
    const val LIGHTUSERDATA = 2
    const val NUMBER = 3
    const val STRING = 4
    const val TABLE = 5
    const val FUNCTION = 6
    const val USERDATA = 7
    const val THREAD = 8
}

internal const val LUA_REGISTRYINDEX = (-1001000)

internal fun pushValue(statePtr: Long, value: LuaValue) {
    when (value) {
        LuaValue.Nil -> LuaNative.pushNil(statePtr)
        is LuaValue.Number -> LuaNative.pushNumber(statePtr, value.value)
        is LuaValue.LuaInt -> LuaNative.pushInteger(statePtr, value.value)
        is LuaValue.Boolean -> LuaNative.pushBoolean(statePtr, value.value)
        is LuaValue.String -> LuaNative.pushString(statePtr, value.value)
        is LuaValue.LightUserData -> LuaNative.pushLightUserdata(statePtr, value.ptr ?: 0L)
        is LuaValue.TableValue -> {
            LuaNative.createTable(statePtr, 0, value.map.size)
            val top = LuaNative.getTop(statePtr)
            for ((k, v) in value.map) {
                pushValue(statePtr, k)
                pushValue(statePtr, v)
                LuaNative.setTable(statePtr, top)
            }
            if (value.metatable != LuaValue.Nil) {
                pushValue(statePtr, value.metatable)
                LuaNative.setMetatable(statePtr, top)
            }
        }
        is LuaValue.FunctionValue -> {
            LuaNative.pushCFunction(statePtr, value.callbackId)
        }
        is LuaValue.FunctionRef -> {
            LuaNative.rawGetI(statePtr, LUA_REGISTRYINDEX, value.refId.toLong())
        }
        is LuaValue.TableRef -> {
            LuaNative.rawGetI(statePtr, LUA_REGISTRYINDEX, value.refId.toLong())
        }
        is LuaValue.UserData -> {
            LuaNative.rawGetI(statePtr, LUA_REGISTRYINDEX, value.refId.toLong())
        }
        else -> throw RuntimeException("${value::class.simpleName} not supported")
    }
}
