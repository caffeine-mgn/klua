package pw.binom.lua

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
     * This wrapper does **not** allocate a Lua state and does **not** register
     * itself anywhere, but it does behave like a regular LuaContext for the
     * duration of the call: `state`, `push`, `readValue` all work against the
     * wrapped pointer. It must not escape the callback scope.
     */
    companion object {
        internal fun wrap(statePtr: Long): LuaContext = LuaContext(statePtr)
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
            -1, 0 -> LuaValue.Nil
            1 -> LuaValue.Boolean(LuaNative.toBoolean(statePtr, index))
            2 -> LuaValue.LightUserData(LuaNative.toUserdata(statePtr, index))
            3 -> LuaValue.Number(LuaNative.toNumber(statePtr, index))
            4 -> LuaValue.String(LuaNative.toString(statePtr, index) ?: "")
            5 -> {
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
            6 -> {
                if (ref) {
                    LuaNative.pushValue(statePtr, index)
                    val refId = LuaNative.ref(statePtr, LUA_REGISTRYINDEX)
                    val ptr = LuaNative.toPointer(statePtr, index)
                    LuaValue.FunctionRef(refId, ptr, this)
                } else {
                    LuaValue.FunctionValue(0)
                }
            }
            7 -> {
                LuaNative.pushValue(statePtr, index)
                val refId = LuaNative.ref(statePtr, LUA_REGISTRYINDEX)
                LuaValue.UserData(refId, this)
            }
            else -> throw RuntimeException("Unknown lua type")
        }
    }

    fun close() {
        val current = statePtr
        if (current != 0L) {
            LuaNative.close(current)
            // Null out so Cleaner actions registered by Kotlin-side wrappers
            // become no-ops rather than touching freed memory.
            statePtr = 0L
        }
    }
}


