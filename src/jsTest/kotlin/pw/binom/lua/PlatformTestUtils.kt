package pw.binom.lua

import org.khronos.webgl.ArrayBuffer
import kotlin.test.BeforeTest

private var loaded = false

actual abstract class AbstractTest {
    actual protected fun start(vararg a: Int, f: () -> Unit) {
        StdOut.info("#111")
    }

    protected fun start(f: () -> Unit) = async {
        StdOut.func = {
            console.info(it + "\n")
            logXhr(it)
        }
        StdOut.info("#222")

        loadScript("http://127.0.0.1:8093/lua_native_single.js")
        loadLua()
        f()
    }

//    @BeforeTest
    fun startup() {
        if (!loaded) {
            StdOut.func = {
                console.info(it + "\n")
                logXhr(it)
            }
            loaded = true
            StdOut.info("Loading script")
            loadScript2("http://127.0.0.1:8093/lua_native_single.js")
//            val asm = loadWasm()
            async {
                StdOut.info("Loading wasm")
//                loadLua(WasmProvider.Binary { asm })
                loadLua()
                StdOut.info("All loaded")
            }
        }
    }
}