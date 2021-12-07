package pw.binom.lua

import kotlinx.browser.document
import org.w3c.dom.HTMLScriptElement
import kotlin.coroutines.*
import kotlin.js.Promise

external fun LuaNative(promise: dynamic): Promise<LuaWasm>

fun loadScript2(path: String) {
    val script = document.createElement("script").unsafeCast<HTMLScriptElement>()
    script.src = path
    script.async = false
    document.head!!.appendChild(script)
}

suspend fun loadScript(path: String) {
    suspendCoroutine<Unit> { continuation ->
        val script = document.createElement("script").unsafeCast<HTMLScriptElement>()
        script.src = path
        script.async = false
        script.onload = {
            continuation.resume(Unit)
        }
        script.onerror = { a: dynamic, b: String, c: Int, d: Int, i: Any? ->
            continuation.resumeWithException(RuntimeException("Can't load script from $path"))
        }
        document.head!!.appendChild(script)
    }
}

val dd: () -> Unit = {

}

fun main() {
    return
    async {
        loadScript("http://127.0.0.1:8093/lua_native.js")
        loadLua()
        WASM_INSTANCE.addFunction(dd, "v")
        val c = LuaEngine()
        console.info(c)
        val o = ObjectContainer()
        c["my_func"] = o.makeClosure {
            println("Hello from kotlin")
            emptyList()
        }
        c["vvv"] = "ddd".lua
//        c.eval("print('Привет Hello world')")
        c.eval("my_func()")
    }
}

fun <T> (suspend () -> T).start2(): Promise<T> {
    val promise = Promise<T> { r1, r2 ->
        this.startCoroutine(object : Continuation<T> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                if (result.isSuccess) {
                    r1(result.getOrNull() as T)
                } else {
                    r2(result.exceptionOrNull()!!)
                }
            }
        })

    }
    return promise
}

fun <P> async(f: suspend () -> P) = f.start2()