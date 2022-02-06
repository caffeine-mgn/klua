package pw.binom.lua

import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaUserdata
import org.luaj.vm2.LuaValue as LuaJValue

class KLuaUserdata(obj: Any?, metatable: LuaJValue = LuaJValue.NIL) : LuaUserdata(obj, metatable) {
    override fun hashCode(): Int =
        if (m_instance == null) {
            0
        } else {
            super.hashCode()
        }

    override fun equals(other: Any?): Boolean {
        if (m_instance == null) {
            return other == null
        }
        return super.equals(other)
    }

    override fun raweq(value: LuaUserdata): Boolean {
        if (m_instance == null) {
            return value.m_instance == null
        }
        if (value.m_instance == null) {
            return m_instance == null
        }
        return super.raweq(value)
    }

    override fun tojstring(): String {
        val str = this.callToString()
        if (str != null) {
            return str
        }
        return "userdata(${hashCode()})"
    }

    override fun tostring(): org.luaj.vm2.LuaValue =
        LuaString.valueOf(tojstring())
}
