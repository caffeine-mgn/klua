package pw.binom.lua

/**
 * Container for Object for pass to [LuaEngine]
 * Make sure you called [ObjectContainer.clear] for cleanup resource. Otherwise can be memory leak
 */
expect class ObjectContainer {
    constructor()

    fun makeClosure(func: LuaFunction): LuaValue.FunctionValue
    fun add(data: Any): LuaValue.LightUserData

    /**
     * Remove [data] from Container
     * @return `true` on successful remove. `false` - object already removed
     */
    fun remove(data: Any): Boolean
    fun get(data: LuaValue.LightUserData): Any?
    fun getClosure(func: LuaValue.FunctionValue): LuaFunction?

    /**
     * Clean all added objects and closures. Call for prevent memory leak
     */
    fun clear()
}