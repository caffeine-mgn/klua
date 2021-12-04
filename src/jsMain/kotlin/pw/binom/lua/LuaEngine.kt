package pw.binom.lua

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise

actual class LuaEngine {
    companion object {
        suspend fun loadLua() = prepareWasmLua()
    }


    actual fun eval(text: String): List<LuaValue> {
        TODO("Not yet implemented")
    }

    /**
     * Returns global variable by [name]
     * @param name name of global variable
     */
    actual operator fun get(name: String): LuaValue {
        TODO("Not yet implemented")
    }

    /**
     * Sets global variable [name] to [value]
     * @param name name of global variable
     * @param value new value of global variable
     */
    actual operator fun set(name: String, value: LuaValue) {
    }

    /**
     * Creates reference to [value]
     */
    actual fun makeRef(value: LuaValue.FunctionValue): LuaValue.FunctionRef {
        TODO("Not yet implemented")
    }

    /**
     * Creates reference to [value]
     */
    actual fun makeRef(value: LuaValue.TableValue): LuaValue.TableRef {
        TODO("Not yet implemented")
    }

    /**
     * Creates userdata from [value]
     * @param value input pointer
     * @return created useddata. Value of userdata is pointer of [value]
     */
    actual fun createUserData(value: LuaValue.LightUserData): LuaValue.UserData {
        TODO("Not yet implemented")
    }

    /**
     * Creates new userdata with [value] as ptr. Also sets `metatable['__gc']` special method for
     * dispose stable ptr to [value]
     */
    actual fun createAC(value: LuaValue.LightUserData): LuaValue.UserData {
        TODO("Not yet implemented")
    }

    /**
     * Creates userdata object with ptr to [value]. Also sets `metatable['__gc']` special method for
     * dispose stable ptr to [value]
     */
    actual fun createAC(value: Any?): LuaValue.UserData {
        TODO("Not yet implemented")
    }

    /**
     * Creates auto clean Closure from [func]. Creates empty userdata. Sets `metatable['__gc']` special method
     * for dispose stable reference to [func]
     * @param func function for create closure
     * @return created userdata with redefined `__call`
     */
    actual fun createACClosure(func: LuaFunction): LuaValue.UserData {
        TODO("Not yet implemented")
    }

    /**
     * Sets [userdata].`metatable['__gc']` special method for dispose stable reference to pointer in [userdata]
     */
    actual fun setAC(userdata: LuaValue.UserData) {
    }

    /**
     * Calls global function with name [functionName] and with [args]
     */
    actual fun call(
        functionName: String,
        vararg args: LuaValue
    ): List<LuaValue> {
        TODO("Not yet implemented")
    }

    /**
     * Calls [value] with [args]
     */
    actual fun call(
        value: LuaValue,
        vararg args: LuaValue
    ): List<LuaValue> {
        TODO("Not yet implemented")
    }

    /**
     * Pin reference to prevent auto-remove by lua GC.
     * @return `true` if object pinned successful. return `false` if object already pinned
     */
    actual fun pin(ref: LuaValue.Ref): Boolean {
        TODO("Not yet implemented")
    }

    /**
     * Unpin reference for allow to auto-remove by lua GC
     * @return `true` if object successful unpinned. Return `false` if object already unpinned
     */
    actual fun unpin(ref: LuaValue.Ref): Boolean {
        TODO("Not yet implemented")
    }

    /**
     * Set of pinned object
     */
    actual val pinned: Set<LuaValue.Ref>
        get() = TODO("Not yet implemented")

    /**
     * Unpin all pinned references
     */
    actual fun freeAllPinned() {
    }
}

suspend fun loadLua() = suspendCoroutine<WebAssembly.Container> { continuation ->
    console.info("Hello from kolin!")
    WebAssembly.instantiateStreaming(fetch("lua.wasm"), JsInterops).then { module ->
        val func = module.asDynamic().instance.exports.cpp_test
        console.info("func=", func)
        console.info("result: ${func(6, 2)}")
        continuation.resume(module)
    }.catch {
        continuation.resumeWithException(it)
    }
}

external object WebAssembly {
    fun instantiateStreaming(d: Response, f: dynamic = definedExternally): Promise<Container>
    fun instantiateStreaming(d: Promise<Response>, f: dynamic = definedExternally): Promise<Container>
    class Module {

    }

    class Instance {
        val exports: dynamic
    }

    class Container {
        val instance: Instance
        val module: Module
    }
}

external fun fetch(address: String): Promise<Response>

external class Response {

}