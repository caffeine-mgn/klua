package pw.binom.lua

import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue as LuaJValue

class KLuaTable(named: Array<LuaJValue>, unnamed: Array<LuaJValue>) :
    LuaTable(named, unnamed, null) {

    override fun tojstring(): String {
        val str = this.callToString()
        if (str != null) {
            return str
        }
        return "table(${hashCode()})"
    }

    override fun tostring(): org.luaj.vm2.LuaValue =
        LuaString.valueOf(tojstring())
}