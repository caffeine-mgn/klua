package pw.binom.lua

import org.luaj.vm2.LuaUserdata
import org.luaj.vm2.LuaValue

class LuaJLightUserdata(obj: Any?) : LuaUserdata(obj) {
    companion object {
        const val TYPE = 30
    }

    override fun getmetatable(): LuaValue = LuaValue.NIL
    override fun setmetatable(metatable: LuaValue?): LuaValue = LuaValue.NIL
    override fun type(): Int = TYPE

    override fun typename(): String = "lightuserdata"
}