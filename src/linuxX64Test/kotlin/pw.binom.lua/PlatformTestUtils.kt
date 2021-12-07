package pw.binom.lua

actual abstract class AbstractTest {
    protected actual fun start(vararg a: Int, f: () -> Unit) {
        StdOut.func={
            println(it)
        }
        f()
    }
}