package pw.binom.lua

import java.util.concurrent.ConcurrentHashMap

internal class LuaContext {
    // Mutable because [close] nullifies it after [LuaNative.close] so that
    // Cleaner actions registered from TableRef/FunctionRef/UserData wrappers
    // can detect "engine already closed" and skip the call — accessing the
    // raw pointer after lua_close is undefined behaviour.
    private var statePtr: Long = 0L

    constructor() {
        statePtr = LuaNative.newState()
        if (statePtr == 0L) throw RuntimeException("Can't create Lua State")
    }

    private constructor(initialState: Long) {
        statePtr = initialState
    }

    /**
     * Stack-only wrapper around an existing [lua_State] pointer, used by
     * callback dispatch ([LuaNative.invokeCallback]) so the bridge has a
     * LuaContext-shaped value to call without going through the full
     * constructor — the state is owned by another LuaContext (the engine's).
     *
     * Each call to [wrap] registers the wrapper in [wrappersByState] keyed
     * by the engine's state pointer so that when the owning engine calls
     * [close] we can null out `statePtr` on every live wrapper. Without
     * this, a callback-returned TableRef/FunctionRef/UserData that
     * outlives `engine.close()` would still hold the wrapper's `statePtr`
     * and its Cleaner would later call `LuaNative.unref(<freed pointer>, …)`
     * — a use-after-free.
     *
     * A Cleaner also drops the wrapper from the map when it becomes
     * phantom-reachable (i.e. the wrapper itself was GC'd) so the map
     * doesn't grow unboundedly across many short-lived callbacks.
     */
    companion object {
        private val wrappersByState = ConcurrentHashMap<Long, MutableList<LuaContext>>()

        internal fun wrap(statePtr: Long): LuaContext {
            val ctx = LuaContext(statePtr)
            wrappersByState
                .computeIfAbsent(statePtr) { mutableListOf() }
                .add(ctx)
            // When the wrapper itself becomes phantom-reachable, remove it
            // from the map so we don't accumulate dead entries.
            LuaValue.REFCLEANER.register(ctx, WrapperCleanup(statePtr, ctx))
            return ctx
        }

        private fun clearWrappersFor(statePtr: Long) {
            wrappersByState.remove(statePtr)?.forEach { wrapper ->
                wrapper.statePtr = 0L
            }
        }

        private class WrapperCleanup(
            private val statePtr: Long,
            private val wrapper: LuaContext,
        ) : Runnable {
            override fun run() {
                wrappersByState[statePtr]?.remove(wrapper)
                if (wrappersByState[statePtr]?.isEmpty() == true) {
                    wrappersByState.remove(statePtr)
                }
            }
        }
    }

    val state: Long
        get() = statePtr

    fun push(value: LuaValue) {
        pushValue(statePtr, value)
    }

    fun readValue(index: Int, ref: Boolean = true): LuaValue {
        val abs = LuaNative.absIndex(statePtr, index)
        return readValueAt(abs, ref)
    }

    private fun readValueAt(index: Int, ref: Boolean): LuaValue {
        return when (LuaNative.type(statePtr, index)) {
            LuaType.NONE, LuaType.NIL -> LuaValue.Nil
            LuaType.BOOLEAN -> LuaValue.Boolean(LuaNative.toBoolean(statePtr, index))
            LuaType.LIGHTUSERDATA -> LuaValue.LightUserData(LuaNative.toUserdata(statePtr, index))
            LuaType.NUMBER -> LuaValue.Number(LuaNative.toNumber(statePtr, index))
            LuaType.STRING -> LuaValue.String(LuaNative.toString(statePtr, index) ?: "")
            LuaType.TABLE -> {
                if (ref) {
                    // Mirror posixNative's makeRef(popValue=false): duplicate the value to the
                    // top so that the ref() pop does not consume the value at the original index.
                    LuaNative.pushValue(statePtr, index)
                    val refId = LuaNative.ref(statePtr, LUA_REGISTRYINDEX)
                    val ptr = LuaNative.toPointer(statePtr, index)
                    // Stack: [.., original_at_index, popped_copy_at_-1_gone]
                    LuaValue.TableRef(refId, ptr, this)
                } else {
                    val map = HashMap<LuaValue, LuaValue>()
                    LuaNative.pushNil(statePtr)
                    while (LuaNative.next(statePtr, index) != 0) {
                        val k = readValueAt(-2, true)
                        val v = readValueAt(-1, true)
                        map[k] = v
                        LuaNative.pop(statePtr, 1)
                    }
                    val hasMeta = LuaNative.getMetatable(statePtr, index) != 0
                    if (hasMeta) {
                        // CRITICAL: the metatable is at -1 on the stack right
                        // now, but the inner recursive readValueAt(_, ref=false)
                        // uses its `index` parameter as the table index for
                        // lua_next. If we passed -1, lua_next would try to walk
                        // whatever happens to be at the stack top AFTER pushNil
                        // — which is nil, not the metatable — and crash in
                        // luaH_next+0x8. Compute the absolute index of the
                        // metatable here, recurse with that, then pop it once.
                        val metaAbs = LuaNative.getTop(statePtr)
                        val meta = readValueAt(metaAbs, false)
                        LuaNative.pop(statePtr, 1)
                        val table = LuaValue.TableValue(map)
                        table.metatable = meta
                        table
                    } else {
                        LuaValue.TableValue(map)
                    }
                }
            }
            LuaType.FUNCTION -> {
                if (ref) {
                    LuaNative.pushValue(statePtr, index)
                    val refId = LuaNative.ref(statePtr, LUA_REGISTRYINDEX)
                    val ptr = LuaNative.toPointer(statePtr, index)
                    LuaValue.FunctionRef(refId, ptr, this)
                } else {
                    LuaValue.FunctionValue(0)
                }
            }
            LuaType.USERDATA -> {
                LuaNative.pushValue(statePtr, index)
                val refId = LuaNative.ref(statePtr, LUA_REGISTRYINDEX)
                LuaValue.UserData(refId, this)
            }
            else -> throw RuntimeException("Unknown lua type: ${LuaNative.type(statePtr, index)}")
        }
    }

    fun close() {
        val current = statePtr
        if (current != 0L) {
            LuaNative.close(current)
            // Null out so Cleaner actions registered by Kotlin-side wrappers
            // become no-ops rather than touching freed memory. Also null
            // out statePtr on every wrapper registered for this engine
            // (see [wrap]) so any callback-returned LuaValue held past
            // engine.close() cannot dereference the freed native state.
            statePtr = 0L
            clearWrappersFor(current)
        }
    }
}


