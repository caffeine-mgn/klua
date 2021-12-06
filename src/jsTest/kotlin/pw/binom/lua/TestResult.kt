package pw.binom.lua

import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.w3c.xhr.ARRAYBUFFER
import org.w3c.xhr.BLOB
import org.w3c.xhr.XMLHttpRequest
import org.w3c.xhr.XMLHttpRequestResponseType
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test

suspend fun waitF() {
    suspendCoroutine<Unit> {
        StdOut.info("Wait forever!")
        window.setInterval({
            println("Wait forever!")
        }, 25000)
    }
}

fun logXhr(txt: String) {
    val xhr = XMLHttpRequest()
    xhr.open(method = "POST", url = "http://127.0.0.1:8093/", async = false)
    xhr.send(txt)
    println(txt)
}

fun loadWasm(): ArrayBuffer {
    val xhr = XMLHttpRequest()
    xhr.open(method = "POST", url = "http://127.0.0.1:8093/lua_native.wasm", async = false)
    console.info("responseType: ${xhr.responseType}")
//    xhr.responseType = XMLHttpRequestResponseType.ARRAYBUFFER
    xhr.send()
    console.info("responseType: ${jsTypeOf(xhr.response)}")
    console.info("responseType: ${xhr.response}")
    return xhr.response.unsafeCast<ArrayBuffer>()
}

external val __filename: String?
external val importScripts: dynamic
external val process: dynamic
var ENVIRONMENT_IS_WEB = jsTypeOf(window) == "object"
var ENVIRONMENT_IS_WORKER = jsTypeOf(importScripts) == "function"
var ENVIRONMENT_IS_NODE =
    jsTypeOf(process) == "object" && jsTypeOf(process.versions) == "object" && jsTypeOf(process.versions.node) == "string"

//fun prepare(f: () -> Unit): Unit = async {
//    logFunc = {
//        logXhr(it)
//    }
//    StdOut.func = {
//        console.info(it + "\n")
//        logXhr(it)
//    }
//    log("try load script...  __filename=$__filename")
//    log("ENVIRONMENT_IS_WEB=$ENVIRONMENT_IS_WEB")
//    log("ENVIRONMENT_IS_WORKER=$ENVIRONMENT_IS_WORKER")
//    log("ENVIRONMENT_IS_NODE=$ENVIRONMENT_IS_NODE")
//
//    log("script loaded. Lua loading")
//
//    log("WASM loaded")
//    f()
//}.unsafeCast<Unit>()


//class VV {
//    @Test
//    fun test() = run {
//        StdOut.func = {
//            console.info(it + "\n")
//            logXhr(it)
//        }
//        StdOut.info("script loaded. Lua loading1")
//        loadScript2("http://127.0.0.1:8093/lua_native.js")
//        StdOut.info("script loaded. Lua loading2")
//        async {
//        loadLua(WasmProvider.Binary { loadWasm() })
//        }
//        StdOut.info("WASM loaded3")
//    }
//}