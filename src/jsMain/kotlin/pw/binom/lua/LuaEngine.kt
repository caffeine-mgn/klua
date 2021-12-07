package pw.binom.lua

import org.khronos.webgl.ArrayBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise
import kotlin.js.json

internal var luaModule: LuaWasm? = null


sealed interface WasmProvider {
    fun interface RemoteFile : WasmProvider {
        fun locate(basePath: String, scriptDirectory: String): String
    }

    fun interface Binary : WasmProvider {
        fun locate(): ArrayBuffer
    }
}

//
suspend fun loadLua(locateFile: WasmProvider? = null) = suspendCoroutine<LuaWasm> { continuation ->
    StdOut.info("loadLua")
    if (luaModule != null) {
        StdOut.info("module already loaded")
        continuation.resume(luaModule!!)
        return@suspendCoroutine
    }
    val options = json()
    when (locateFile) {
        is WasmProvider.RemoteFile -> options["locateFile"] = locateFile::locate
        is WasmProvider.Binary -> options["wasmBinary"] = locateFile.locate()
    }
    StdOut.info("Call loading")
    LuaNative(options).then { module ->
        StdOut.info("Loading finished success")
        luaModule = module
        StdOut.info("Loading finished success1")
        continuation.resume(module)
    }.catch {
        StdOut.info("Loading finished failed: ${it?.asDynamic().message}  ${it == null}")
        continuation.resumeWithException(it)
    }
//    WebAssembly.instantiateStreaming(fetch("lua.wasm"), JsInterops).then { module ->
//        val func = module.asDynamic().instance.exports.cpp_test
//        console.info("func=", func)
//        console.info("result: ${func(6, 2)}")
//        continuation.resume(module)
//    }.catch {
//        continuation.resumeWithException(it)
//    }
}

external object WebAssembly {
    fun instantiateStreaming(d: Response, f: dynamic = definedExternally): Promise<Container>
    fun instantiateStreaming(d: Promise<Response>, f: dynamic = definedExternally): Promise<Container>
    class Module {

    }

    class Instance {
        val exports: dynamic
    }

    class Memory : ArrayBuffer

    class Container {
        val instance: Instance
        val module: Module
    }
}

external fun fetch(address: String): Promise<Response>

external class Response {

}