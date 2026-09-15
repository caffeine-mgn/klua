package pw.binom.lua

actual class ObjectContainer actual constructor() {

    private val closures = HashMap<Int, LuaFunction>()

    actual fun makeClosure(func: LuaFunction): LuaValue.FunctionValue {
        val id = nextClosureId()
        synchronized(closures) { closures[id] = func }
        val bridge = LuaCallbackBridge { ctx ->
            val fn = synchronized(closures) { closures[id] }
                ?: return@LuaCallbackBridge emptyList<LuaValue>()
            val top = LuaNative.getTop(ctx.state)
            val args = (1..top).map { ctx.readValue(it, true) }
            LuaNative.pop(ctx.state, top)
            fn.call(args)
        }
        LuaNative.setCallback(id, bridge)
        return LuaValue.FunctionValue(id)
    }

    actual fun add(data: Any?): LuaValue.LightUserData {
        val ptr = StaticRefs.intern(data)
        return LuaValue.LightUserData(ptr)
    }

    actual fun get(data: LuaValue.LightUserData): Any? = StaticRefs.get(data.ptr)

    actual fun remove(data: Any): Boolean = StaticRefs.removeIfMatches(data)

    actual fun getClosure(func: LuaValue.FunctionValue): LuaFunction? =
        synchronized(closures) { closures[func.callbackId] }

    actual fun clear() {
        synchronized(closures) { closures.clear() }
    }

    actual fun removeClosure(data: LuaValue.FunctionRef): Boolean = false
    actual fun removeClosure(data: LuaValue.FunctionValue): Boolean {
        val removed = synchronized(closures) { closures.remove(data.callbackId) != null }
        return removed
    }

    companion object {
        private val counter = java.util.concurrent.atomic.AtomicInteger(1)
        fun nextClosureId(): Int = counter.getAndIncrement()
    }
}
