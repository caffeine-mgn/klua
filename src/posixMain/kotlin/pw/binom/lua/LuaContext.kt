package pw.binom.lua

import kotlinx.cinterop.ExperimentalForeignApi
import platform.internal_lua.luaL_openlibs
import platform.internal_lua.lua_close
import platform.internal_lua.luaL_unref
import platform.internal_lua.LUA_REGISTRYINDEX
import platform.internal_lua.klua_control
import platform.internal_lua.klua_control_free
import platform.internal_lua.klua_control_reason
import platform.internal_lua.klua_control_reset
import platform.internal_lua.klua_control_set_cancel
import platform.internal_lua.klua_control_set_timeout
import platform.internal_lua.klua_control_new
import platform.internal_lua.klua_newstate
import kotlin.concurrent.AtomicInt
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner
import kotlinx.cinterop.CPointer

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal class LuaContext(limits: LuaLimits = LuaLimits.UNLIMITED) {
    // Native limit control block (allocator cap + instruction/timeout/cancel
    // hook). Shared with the JNI backend through src/nativeMain/limits.
    private val control: CPointer<klua_control> = klua_control_new(
        limits.maxMemoryBytes ?: 0L,
        limits.maxInstructions ?: 0L,
        0L,
    ) ?: throw RuntimeException("Can't allocate Lua limit control")

    val state: LuaState = klua_newstate(control)
        ?: run {
            klua_control_free(control)
            throw RuntimeException("Can't create Lua State")
        }

    init {
        // A fresh Lua state is intentionally bare — no standard library is
        // loaded by default. Use [openLibs] (or [LuaEngine.openLibs]) to opt
        // into a specific subset, or [openStandardLibs] for all of them.
        LuaContextRegistry.register(state, this)
    }

    // Idempotent close shared by [close] and the Cleaner. The resources are
    // bundled in a separate holder so the Cleaner's lambda is non-capturing
    // (a Kotlin/Native requirement) and never references the LuaContext.
    private val resources = LuaContextResources(state, control)

    /**
     * Очередь отложенного освобождения Lua-ref'ов.
     *
     * klua освобождает ref'ы из финализатора GC-потока. Вызывать оттуда
     * `luaL_unref` нельзя: (1) состояние может быть уже закрыто — это
     * use-after-free и SIGSEGV; (2) вызов шёл бы параллельно с обычными
     * операциями над `lua_State`, который не потокобезопасен. Поэтому
     * финализатор только складывает id в очередь (это единственное, что он
     * делает с состоянием), а настоящий `luaL_unref` выполняется на
     * «хозяйской» нити в [drainRefDisposals], перед следующей операцией с Lua.
     */
    private val pendingRefs = RefDisposalQueue()

    private val cleaner = createCleaner(resources) { res ->
        if (res.closed.compareAndSet(0, 1)) {
            LuaContextRegistry.unregister(res.state)
            lua_close(res.state)
            klua_control_free(res.control)
        }
    }

    /**
     * Складывает ref в очередь отложенного освобождения. Безопасно вызывать
     * с любой нити (в том числе из финализатора): не трогает `lua_State`.
     */
    internal fun enqueueRefDisposal(ref: LuaRef) {
        pendingRefs.push(ref.id)
    }

    /**
     * Освобождает ref'ы, накопленные финализатором. Обязан вызываться с той
     * же «хозяйской» нити, что и остальные операции с этим состоянием, и не
     * во время активного вызова Lua.
     */
    internal fun drainRefDisposals() {
        if (pendingRefs.isEmpty()) return
        val ids = pendingRefs.drain()
        // Состояние уже закрыто — unref'ить нечего, и указатель невалиден.
        if (resources.closed.value != 0) return
        ids.forEach { luaL_unref(state, LUA_REGISTRYINDEX, it) }
    }

    /**
     * Loads exactly the given [libraries] into this context's state via
     * `luaL_requiref`, one library at a time.
     */
    fun openLibs(libraries: Set<LuaLibrary>) {
        state.openLibs(libraries)
    }

    /**
     * Loads every Lua 5.4 standard library into this context's state via
     * [luaL_openlibs]. See [pw.binom.lua.LuaEngine.openStandardLibs].
     */
    fun openStandardLibs() {
        luaL_openlibs(state)
    }

    /** Releases the Lua state and the limit control block exactly once. */
    fun close() {
        if (resources.closed.compareAndSet(0, 1)) {
            // Финализаторы могли успеть накопить ref'ы — состояние всё равно
            // освобождается целиком, отдельный unref не нужен.
            pendingRefs.clear()
            LuaContextRegistry.unregister(state)
            lua_close(state)
            klua_control_free(control)
        }
    }

    /** Clears the per-call instruction counter, cancellation flag and last reason. */
    fun resetLimits() {
        klua_control_reset(control)
    }

    /** Arms the wall-clock deadline for the current execution (0 = disarmed). */
    fun setLimitTimeout(timeoutMicros: Long) {
        klua_control_set_timeout(control, timeoutMicros)
    }

    /** Sets the cooperative cancellation flag checked by the limit hook. */
    fun setLimitCancel(cancel: Boolean) {
        klua_control_set_cancel(control, if (cancel) 1 else 0)
    }

    /** Last limit reason (KLUA_REASON_*), 0 if none. */
    fun lastLimitReason(): Int = klua_control_reason(control)
}

/**
 * Потокобезопасная очередь id Lua-ref'ов, ожидающих `luaL_unref`.
 *
 * Push идёт из финализатора GC-потока, drain — с «хозяйской» нити состояния.
 * Реализация на спинлоке: операции короткие (добавить/забрать int), а
 * зависимости от atomicfu/блокирующих мьютексов не нужны.
 */
@OptIn(ExperimentalNativeApi::class)
internal class RefDisposalQueue {
    private val lock = AtomicInt(0)
    private val count = AtomicInt(0)
    private val items = ArrayList<Int>()

    private inline fun <T> locked(block: () -> T): T {
        while (!lock.compareAndSet(0, 1)) {
            // Короткая критическая секция: только работа с ArrayList.
        }
        try {
            return block()
        } finally {
            lock.value = 0
        }
    }

    fun push(id: Int) {
        locked { items.add(id) }
        count.addAndGet(1)
    }

    /** Дешёвая проверка без захвата лока — только чтобы пропустить пустой случай. */
    fun isEmpty(): Boolean = count.value == 0

    fun drain(): List<Int> {
        val out = locked {
            if (items.isEmpty()) {
                emptyList()
            } else {
                val copy = ArrayList(items)
                items.clear()
                copy
            }
        }
        if (out.isNotEmpty()) {
            count.addAndGet(-out.size)
        }
        return out
    }

    fun clear() {
        locked { items.clear() }
        count.value = 0
    }
}

/**
 * Owns the native resources of a [LuaContext] (Lua state + limit control
 * block) together with an idempotency flag. Bundled into a single object so
 * the `createCleaner` lambda below is non-capturing.
 */
@OptIn(ExperimentalForeignApi::class)
private class LuaContextResources(
    val state: LuaState,
    val control: CPointer<klua_control>,
) {
    val closed = AtomicInt(0)
}

/**
 * Global mapping from LuaState → LuaContext. Each LuaContext owns a unique
 * LuaState created by [klua_newstate]; when the engine is created (and the
 * LuaContext created) we register it here so C-side closures (CLOSURE_FUNCTION,
 * closureGc, userdataGc) can recover the [LuaContext] from the state pointer
 * they're given without round-tripping through upvalues.
 *
 * A multi-slot map is required (not the previous single-slot `current` Pair):
 * a second LuaEngine in the same process overwrites the slot, and the first
 * engine's callbacks then silently no-op in CLOSURE_FUNCTION or leak StableRefs
 * in userdataGc because `lookup(state_of_first) -> null`. Tests that spin up
 * multiple engines — and any service that scopes an engine per request — hit
 * this in production.
 */
@OptIn(ExperimentalForeignApi::class)
internal object LuaContextRegistry {
    // Concurrency: callback dispatch is single-threaded per engine on POSIX
    // (Lua 5.4's lua_State is not thread-safe), and engine construction is
    // typically serial in embeddings. Plain HashMap without explicit locking
    // is sufficient — multi-engine *lookup* happens on whichever thread the
    // engine's callback runs, but a single lua_State is owned by exactly one
    // thread. Concurrent LuaEngine *construction* from multiple threads is
    // the caller's contract to serialise.
    private val map = HashMap<LuaState, LuaContext>()

    // Финализатор LuaContext вызывает unregister() с нити GC, поэтому карта
    // должна быть защищена: спинлок на короткие операции register/lookup.
    private val lock = AtomicInt(0)

    private inline fun <T> locked(block: () -> T): T {
        while (!lock.compareAndSet(0, 1)) {
        }
        try {
            return block()
        } finally {
            lock.value = 0
        }
    }

    fun register(state: LuaState, ctx: LuaContext) = locked { map[state] = ctx }

    fun unregister(state: LuaState) = locked { map.remove(state) }

    fun lookup(state: LuaState): LuaContext? = locked { map[state] }
}
