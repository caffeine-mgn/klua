package pw.binom.lua

internal class LuaContext(val state: Long) {
    constructor() : this(LuaNative.newState().also {
        if (it == 0L) throw RuntimeException("Can't create Lua State")
    })

    fun push(value: LuaValue) {
        pushValue(state, value)
    }

    fun readValue(index: Int, ref: Boolean = true): LuaValue {
        val abs = LuaNative.absIndex(state, index)
        return readValueAt(abs, ref)
    }

private fun readValueAt(index: Int, ref: Boolean): LuaValue {
    return when (LuaNative.type(state, index)) {
        -1, 0 -> LuaValue.Nil
        1 -> LuaValue.Boolean(LuaNative.toBoolean(state, index))
        2 -> LuaValue.LightUserData(LuaNative.toUserdata(state, index))
        3 -> LuaValue.Number(LuaNative.toNumber(state, index))
        4 -> LuaValue.String(LuaNative.toString(state, index) ?: "")
        5 -> {
            if (ref) {
                // Mirror posixNative's makeRef(popValue=false): duplicate the value to the
                // top so that the ref() pop does not consume the value at the original index.
                LuaNative.pushValue(state, index)
                val refId = LuaNative.ref(state, LUA_REGISTRYINDEX)
                val ptr = LuaNative.toPointer(state, index)
                // Stack: [.., original_at_index, popped_copy_at_-1_gone]
                LuaValue.TableRef(refId, ptr, this)
            } else {
                    val map = HashMap<LuaValue, LuaValue>()
                    LuaNative.pushNil(state)
                    while (LuaNative.next(state, index) != 0) {
                        val k = readValueAt(-2, true)
                        val v = readValueAt(-1, true)
                        map[k] = v
                        LuaNative.pop(state, 1)
                    }
                    val hasMeta = LuaNative.getMetatable(state, index) != 0
                    val meta = if (hasMeta) readValueAt(-1, true) else LuaValue.Nil
                    if (hasMeta) LuaNative.pop(state, 1)
                    val table = LuaValue.TableValue(map)
                    table.metatable = meta
                    table
                }
            }
            6 -> {
                if (ref) {
                    LuaNative.pushValue(state, index)
                    val refId = LuaNative.ref(state, LUA_REGISTRYINDEX)
                    val ptr = LuaNative.toPointer(state, index)
                    LuaValue.FunctionRef(refId, ptr, this)
                } else {
                    LuaValue.FunctionValue(0)
                }
            }
            7 -> {
                LuaNative.pushValue(state, index)
                val refId = LuaNative.ref(state, LUA_REGISTRYINDEX)
                LuaValue.UserData(refId, this)
            }
            else -> throw RuntimeException("Unknown lua type")
        }
    }

    fun close() {
        LuaNative.close(state)
    }
}

