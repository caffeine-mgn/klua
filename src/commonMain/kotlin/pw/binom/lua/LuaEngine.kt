package pw.binom.lua

expect class LuaEngine {
    constructor()

    fun eval(text: String): List<LuaValue>
    operator fun get(name: String): LuaValue
    operator fun set(name: String, value: LuaValue)
    fun makeRef(value: LuaValue.FunctionValue): LuaValue.FunctionRef
    fun makeRef(value: LuaValue.TableValue): LuaValue.TableRef
    fun call(functionName: String, vararg args: LuaValue): List<LuaValue>
    fun call(value: LuaValue, vararg args: LuaValue): List<LuaValue>

    /**
     * Pin reference to prevent auto-remove by lua GC.
     * @return `true` if object pinned successful. return `false` if object already pinned
     */
    fun pin(ref: LuaValue.Ref):Boolean

    /**
     * Unpin reference for allow to auto-remove by lua GC
     * @return `true` if object successful unpinned. Return `false` if object already unpinned
     */
    fun unpin(ref: LuaValue.Ref):Boolean

    /**
     * Set of pinned object
     */
    val pinned: Set<LuaValue.Ref>

    /**
     * Unpin all pinned references
     */
    fun freeAllPinned()
}
