package pw.binom.lua

abstract class AbstractTest {
    constructor()

    /**
     * Builds a [LuaEngine] with the full standard library loaded. Use this
     * in tests instead of the bare `LuaEngine()` constructor — since
     * Round 6/E2 the engine is intentionally bare by default and the
     * embedder is responsible for opting in via [openStandardLibs]. This
     * helper mirrors the historical behaviour so existing test expectations
     * (`pcall`, `error`, `print`, `collectgarbage`, `type`, ...) keep
     * working without per-test boilerplate.
     */
    protected fun makeEngine(): LuaEngine =
        LuaEngine().apply { openStandardLibs() }

    protected fun start(vararg a: Int, f: () -> Unit){
        StdOut.func = {
            println(it)
        }
        f()
    }
}
