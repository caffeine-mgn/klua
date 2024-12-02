package pw.binom.lua

expect class LuaEngine : AutoCloseable {
    constructor()

    val closureAutoGcFunction: LuaValue.FunctionRef
    val userdataAutoGcFunction: LuaValue.FunctionRef
    fun eval(text: String): List<LuaValue>

    /**
     * Returns global variable by [name]
     * @param name name of global variable
     */
    operator fun get(name: String): LuaValue

    /**
     * Sets global variable [name] to [value]
     * @param name name of global variable
     * @param value new value of global variable
     */
    operator fun set(name: String, value: LuaValue)

    /**
     * Creates reference to [value]
     */
    fun makeRef(value: LuaValue.FunctionValue): LuaValue.FunctionRef

    /**
     * Creates reference to [value]
     */
    fun makeRef(value: LuaValue.TableValue): LuaValue.TableRef

    /**
     * Creates userdata from [value]
     * @param value input pointer
     * @return created useddata. Value of userdata is pointer of [value]
     */
    fun createUserData(value: LuaValue.LightUserData): LuaValue.UserData

    /**
     * Creates new userdata with [value] as ptr. Also sets `metatable['__gc']` special method for
     * dispose stable ptr to [value]
     */
    fun createAC(value: LuaValue.LightUserData): LuaValue.UserData

    /**
     * Creates userdata object with ptr to [value]. Also sets `metatable['__gc']` special method for
     * dispose stable ptr to [value]
     */
    fun createAC(value: Any?): LuaValue.UserData

    /**
     * Creates auto clean Closure from [func]. Creates empty userdata. Sets `metatable['__gc']` special method
     * for dispose stable reference to [func]
     * @param func function for create closure
     * @return created userdata with redefined `__call`
     */
    fun createACClosure(func: LuaFunction): LuaValue.UserData

    /**
     * Sets [userdata].`metatable['__gc']` special method for dispose stable reference to pointer in [userdata]
     */
    fun setAC(userdata: LuaValue.UserData)

    /**
     * Calls global function with name [functionName] and with [args]
     */
    fun call(functionName: String, vararg args: LuaValue): List<LuaValue>

    /**
     * Calls [value] with [args]
     */
    fun call(value: LuaValue, vararg args: LuaValue): List<LuaValue>
}
