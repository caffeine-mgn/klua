package pw.binom.lua

import org.khronos.webgl.Int8Array
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.json

private var internalLua: LuaWasm? = null
internal val LUA: LuaWasm
    get() = internalLua
        ?: throw IllegalStateException("Lua not loaded. Make sure you are called pw.binom.lua.LuaEngine.loadLua()")

external class LuaWasm{
    val asm:LuaAsm
    val HEAP8:Int8Array
    fun addFunction(func:dynamic,signature:String):NativePtr1
}

external class LuaAsm{
    val memory:WebAssembly.Memory
}

internal suspend fun prepareWasmLua() {
    suspendCoroutine<Unit> { continuation ->
        val wasm = fetch("lua.wasm")
        val c = LuaNative(json("wasm" to wasm)).then {
            internalLua = it
            console.dir(it)
            continuation.resume(Unit)
        }.catch {
            continuation.resumeWithException(it)
        }
    }
}