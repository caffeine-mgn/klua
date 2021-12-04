package pw.binom.lua

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.js.Promise

external fun KLuaWasm(promise: dynamic): Promise<LuaWasm>
fun main() {
    async {
        LuaEngine.loadLua()
        val c = LuaEngine()
        console.info(c)
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