package pw.binom.lua

actual class ObjectContainer actual constructor() {

    // closures map is intentionally a *separate* object (not just a field of
    // this class) so the bridge lambdas we hand to LuaNative.callbacks can
    // reference it without also capturing a strong reference back to this
    // container — a back-reference would form an ObjectContainer ↔ callbacks
    // cycle that prevents this container from ever becoming
    // phantom-reachable, which would prevent the Cleaner from firing, which
    // would prevent the bridges from being unregistered. The cycle would
    // only resolve when the engine itself is torn down.
    private val closures = ClosureMap()

    actual fun makeClosure(func: LuaFunction): LuaValue.FunctionValue {
        // Allocate the bridge id from the SHARED LuaNative counter. The
        // previous per-container counter started at 1 and collided with
        // LuaNative.nextCallbackId() (also starting at 1): both index the same
        // LuaNative.callbacks map, so a makeClosure and a createACClosure could
        // land on the same id and one bridge silently replaced the other — a
        // Lua call then dispatched to the WRONG Kotlin lambda
        // (AUDIT-2026-10-12.md #1).
        val id = LuaNative.nextCallbackId()
        closures[id] = func
        // Capture the ClosureMap instance, not `this`. The bridge only ever
        // touches `closures` (a leaf object from the cycle's perspective),
        // so when this container goes out of scope the Cleaner can fire.
        val closuresRef = closures
        val bridge = LuaCallbackBridge { ctx ->
            val fn = closuresRef[id] ?: return@LuaCallbackBridge emptyList<LuaValue>()
            val top = LuaNative.getTop(ctx.state)
            val args = (1..top).map { ctx.readValue(it, true) }
            LuaNative.pop(ctx.state, top)
            fn.call(args)
        }
        LuaNative.setCallback(id, bridge)
        // Per-bridge Cleaner action. Captures only the bridge id (primitive)
        // and an unregister lambda that touches the static LuaNative —
        // nothing references `this` or any of its fields.
        LuaValue.REFCLEANER.register(
            this,
            BridgeCleaner(id) { LuaNative.unregisterCallback(it) },
        )
        return LuaValue.FunctionValue(id)
    }

    actual fun add(data: Any?): LuaValue.LightUserData {
        val ptr = StaticRefs.intern(data)
        return LuaValue.LightUserData(ptr)
    }

    actual fun get(data: LuaValue.LightUserData): Any? = StaticRefs.get(data.ptr)

    actual fun remove(data: Any): Boolean = StaticRefs.removeIfMatches(data) > 0

    actual fun getClosure(func: LuaValue.FunctionValue): LuaFunction? =
        closures[func.callbackId]

    actual fun clear() {
        // The previous implementation just emptied `closures` without
        // unregistering the corresponding bridges from LuaNative.callbacks,
        // so the JVM-global callback map grew by one entry per makeClosure
        // call until the owning container's BridgeCleaner fired at GC
        // time — often much later than the caller's intent of "release
        // immediately". Iterate the live ids, unregister each, then drop
        // the local map.
        val ids = closures.snapshotIds()
        ids.forEach { LuaNative.unregisterCallback(it) }
        closures.clear()
    }

    actual fun removeClosure(data: LuaValue.FunctionRef): Boolean =
        removeClosure(data.toValue())

    actual fun removeClosure(data: LuaValue.FunctionValue): Boolean {
        // Drop the local entry AND the JVM-global bridge. Without the latter
        // the LuaNative.callbacks entry survived until the container itself was
        // GC'd (AUDIT-2026-10-12.md #4 / old W2). unregisterCallback is safe to
        // call twice — the container's BridgeCleaner may also drop it later.
        val removed = closures.remove(data.callbackId) != null
        if (removed) LuaNative.unregisterCallback(data.callbackId)
        return removed
    }

    /**
     * Cleaner action for a single bridge: invoked when its owning
     * [ObjectContainer] becomes phantom-reachable. Hands the bridge id back
     * to [LuaNative.unregisterCallback] so the JVM-global `callbacks` map
     * doesn't grow unboundedly across many short-lived containers.
     */
    private class BridgeCleaner(
        private val bridgeId: Int,
        private val unregister: (Int) -> Unit,
    ) : Runnable {
        override fun run() {
            try {
                unregister(bridgeId)
            } catch (_: Throwable) {
                // Bridge cleanup is best-effort; engine.close() would tear
                // the whole Lua state down anyway.
            }
        }
    }
}

/**
 * Internal closure storage for [ObjectContainer]. Promoted to a top-level
 * class so the bridge lambdas registered in [LuaNative.callbacks] can hold
 * a reference to this storage *without* implicitly capturing the containing
 * ObjectContainer — see the note in [ObjectContainer] for why that matters.
 *
 * Not thread-safe by itself; [ObjectContainer.makeClosure] serializes
 * mutations through its own locks. Reads are atomic in the sense that the
 * HashMap assignment is atomic, but iteration is not. We don't iterate.
 */
private class ClosureMap {
    private val map = HashMap<Int, LuaFunction>()

    operator fun set(id: Int, func: LuaFunction) {
        synchronized(map) { map[id] = func }
    }

    operator fun get(id: Int): LuaFunction? =
        synchronized(map) { map[id] }

    fun remove(id: Int): LuaFunction? =
        synchronized(map) { map.remove(id) }

    fun clear() {
        synchronized(map) { map.clear() }
    }

    fun snapshotIds(): List<Int> = synchronized(map) { map.keys.toList() }
}
