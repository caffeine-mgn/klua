package pw.binom.lua

abstract class AbstractTest {
    constructor()

    protected fun start(vararg a: Int, f: () -> Unit){
        StdOut.func = {
            println(it)
        }
        f()
    }
}
