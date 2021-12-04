package pw.binom.lua

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.json

private var internalLua: LuaWasm? = null
internal val LUA: LuaWasm
    get() = internalLua
        ?: throw IllegalStateException("Lua not loaded. Make sure you are called pw.binom.lua.LuaEngine.loadLua()")

external class LuaWasm{

}

internal suspend fun prepareWasmLua() {
    suspendCoroutine<Unit> { continuation ->
        val wasm = fetch("lua.wasm")
        val c = KLuaWasm(json("wasm" to wasm)).then {
            internalLua = it
            console.dir(it)
            continuation.resume(Unit)
        }.catch {
            continuation.resumeWithException(it)
        }
    }
}