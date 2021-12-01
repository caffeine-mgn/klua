package pw.binom.lua

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaUserdata
import org.luaj.vm2.LuaValue

internal fun LuaValue.callToString(): String? {
    val m_metatable = getmetatable()
    if (m_metatable == null || m_metatable == LuaValue.NIL) {
        return null
    }
    val toStr = m_metatable["__tostring"]
    if (toStr == LuaUserdata.NIL) {
        return null
    }
    val stringResult = toStr.call()
    if (stringResult.isstring() || stringResult.isboolean() || stringResult.isnumber()) {
        return stringResult.tojstring()
    }
    throw LuaError("function __string should return string")
}