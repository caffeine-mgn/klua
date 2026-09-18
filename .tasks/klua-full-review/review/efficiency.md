# Efficiency Review — klua

Scope: per-call allocation, JNI crossings, contention, closures, GC pressure.
Read: commonMain + jvmMain + posixMain LuaValue/LuaEngine/ObjectContainer/LuaContext/LuaNative and helpers.

HEAD: fd0aed1.

---

## Findings

### F1. `StaticRefs` uses one global monitor for all get/store/dispose
- **Type:** Concurrency, Performance
- **Severity:** Warning
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/StaticRefs.kt:6-44` (whole file: `private val map = HashMap<Long, Any?>()` + 5× `synchronized(map)` sites)
- **Description:** Every `intern`, `store`, `get`, `dispose`, `removeIfMatches` and `size` takes the same `synchronized(map)` monitor. `StaticRefs.get(key)` is invoked on every `LuaValue.UserData.value` and `LuaValue.LightUserData.value` read (and on every `ObjectContainer.get(LightUserData)`) — all from the JNI bridge, all on whatever thread the engine's caller is on. Under multi-threaded Lua use (the realistic JVM deployment) every value round-trip serializes on this single monitor; `ConcurrentHashMap` is already used elsewhere in this codebase (`LuaNative.callbacks`) and would remove both the lock and the contention. `removeIfMatches` additionally is an O(n) linear scan; for a long-lived engine it can scan hundreds of entries per call.
- **Fix sketch:** Replace the `HashMap` with `java.util.concurrent.ConcurrentHashMap<Long, Any?>` and drop the `synchronized` blocks. For `removeIfMatches`, maintain a reverse `IdentityHashMap<Any?, MutableList<Long>>` if `remove(data)` is on the hot path; otherwise leave it as the rare path it currently is.

### F2. `LuaContext.readValue` always calls `LuaNative.absIndex` even for positive indices
- **Type:** Performance
- **Severity:** Warning
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/LuaContext.kt:41-43` — `val abs = LuaNative.absIndex(statePtr, index)`
- **Description:** `absIndex` is a native call dispatched into JNI on every `readValue`/`readValueAt`. The hot call sites pass positive indices: `pcallProcessing` (`(1..count).map { ll.readValue(it, true) }`) and `pcallCall` (same), and the inner `readValueAt(-2,-1)` calls during table walk — all positive after the first. absIndex is a no-op for positive indices in C but still costs one full JNI boundary crossing per value read; for a Lua function returning 10 results that's 10 wasted crossings on top of the 10 `type` crossings.
- **Fix sketch:** `val abs = if (index > 0) index else LuaNative.absIndex(statePtr, index)` — elides the JNI round-trip for the common case. Consider also batching: when reading N result slots of the same known type, expose `readValues(start, count)` that does one `lua_type` per slot but reuses an `absIndex` fast path.

### F3. `UserData.ptr` recomputed on every property access — 3 JNI crossings per read
- **Type:** Performance
- **Severity:** Warning
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:36-42` — `val ptr: Long? get() { ll.push(this); val p = LuaNative.userdataPtr(ll.state, -1); LuaNative.pop(ll.state, 1); ... }`; `value` at line 46-49 dereferences `ptr` then calls `StaticRefs.get(p)` (one more monitor + map lookup — see F1); `toString()` at line 64 also calls `ptr`. The Cleaner action `UserDataAction.run()` at line 89-97 reproduces the same dance.
- **Description:** `userdata.value` is the most common read on an AC value (table iteration over userdata-backed objects, `getter` callbacks in Lua, etc.). Each call costs `push(this)` JNI + `userdataPtr` JNI + `pop` JNI + `StaticRefs.get` (F1). The userdata's memory address is stable for the lifetime of the userdata — the registry ref (`refId`) keeps the Lua userdata pinned, so the address cannot change. Caching once (lazily on first access or eagerly in the constructor from `lua_newuserdata`'s return) would drop 3 JNI crossings per access.
- **Fix sketch:** Cache `private val cachedPtr: Long?` computed at construction time. The Cleaner action must invalidate or recompute (since it runs at GC after possible engine close); the safer pattern is a `@Volatile var cachedPtr: Long? = null` populated on first access, revalidated lazily, or recomputed inside the Cleaner via `userdataPtr` exactly as today. Even just caching on `ptr` getter (volatile) eliminates 2 of the 3 JNI crossings on subsequent calls.

### F4. POSIX `checkState { }` adds `lua_gettop` × 2 to every table/function access
- **Type:** Performance
- **Severity:** Warning
- **Location:** `src/posixMain/kotlin/pw/binom/lua/lua_funcs.kt:23-31` — `internal inline fun <T> LuaState.checkState(func: () -> T): T { val top = lua_gettop(this); ... val newTop = lua_gettop(this); check(...) }`; used by every `TableRef.get/set/rawGet/rawSet/size/rawSize/toValue` and `FunctionRef.toValue` (`src/posixMain/kotlin/pw/binom/lua/LuaValue.kt:212-310`).
- **Description:** `checkState` is a defensive stack-balance check. Every TableRef operation pays 2 extra C-side `lua_gettop` calls plus a try/finally (inlined, no lambda allocation). For a Lua function that does `for k,v in pairs(t) do ... end` over a 10k-entry table, the per-iteration cost is dominated by these 2 extra native calls. The JVM side has no equivalent, so behaviour diverges between targets.
- **Fix sketch:** Gate `checkState` behind a debug-only flag (`-Dpw.binom.lua.checkStack=true`) or a `LuaEngine` ctor param. In release builds inline the body without the top-check, or expose a `checkStateDisabled {}` overload used by all hot paths. The rawGet/rawSet fix in 6dc634d was already covered by the tableRawGetSetTest; once confidence is established, the guard is pure overhead.

### F5. `pcallCall` allocates `args.toList()` from the vararg array every call
- **Type:** Performance
- **Severity:** WeakWarning
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:56,269,310` — `return pcallCall(ll, args.toList())`; also `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:430-459` (`pcallCall` body uses `args.forEach { pushValue(...) }` and `args.size`).
- **Description:** `args` arrives as a fresh `Array<LuaValue>` from the vararg spread. `args.toList()` copies it into an `ArrayList` just so `pcallCall` can iterate and call `.size`. `pcallCall` only needs indexed access and length — a `for (i in args.indices) pushValue(state, args[i])` loop avoids the List+iterator allocation. Same JVM allocation pattern repeats on every callback dispatch via `LuaNative.invokeCallback` (which iterates `results.forEach { ctx.push(it) }`, line 158).
- **Fix sketch:** Change `pcallCall(ll: LuaContext, args: List<LuaValue>)` to `pcallCall(ll: LuaContext, args: Array<out LuaValue>)` (and drop the varargs allocation at the caller — `pcallCall(ll, args)` works directly). For `invokeCallback`, `for (i in results.indices) ctx.push(results[i])` removes the iterator. Both save 1 ArrayList + 1 iterator per call.

### F6. JVM `LuaValue.LightUserData` + `UserData` Cleaners register per Lua-side read
- **Type:** Performance
- **Severity:** Warning
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/LuaContext.kt:71-80` (lightuserdata read) and `:96-103` (userdata read); cleaners at `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:22-25` (UserData), `:108-117` (LightUserData), `:331-337` (TableRef/FunctionRef RefAction).
- **Description:** Every read of a Lua-side lightuserdata / userdata / table / function reference creates a wrapper *and* registers a `Cleanable` with the shared `LuaValue.REFCLEANER`. A loop like `for _, ud in ipairs(udArray) do ... end` on the Kotlin side ends up iterating Lua values whose wrappers each cost: 1 wrapper object + 1 `Cleanable` (java.lang.ref.Cleaner.Cleanable) + 1 inner `Runnable` (UserDataAction / LightUserDataAction / RefAction) + an entry in Cleaner's internal doubly-linked phantom-list. For an engine that pumps 10⁶ table entries through the bridge, this is millions of long-lived nodes on the Cleaner list before the GC catches up — `Cleaner` is single-threaded by default and its clean queue grows with every registration. The lightuserdata case also has a lifecycle subtlety (the Cleaner disposes the StaticRefs entry on phantom-reachability, which can race with `ObjectContainer.remove(data)` for values that the engine still owns), but flagging the allocation pressure is enough here.
- **Fix sketch:** Either (a) skip Cleaner registration for values that are only used ephemerally (read once, discarded) — e.g. return `Ref` types as a lightweight view that wraps the refId and `ll`, allocating the Cleaner only on first `ptr`/dispose access — or (b) batch phantom-references through a single `ReferenceQueue<LuaValue.Ref>` polled by one daemon thread instead of one Cleaner per object. The `REFCLEANER` itself is a `Cleaner.create()` with no thread-pool argument, so even its daemon is single-threaded.

### F7. JVM `createACClosure` / `setAC` build a `TableValue` per call — two maps and a Pair
- **Type:** Performance
- **Severity:** WeakWarning
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:158-176` (`createACClosure`) and `:185-192` (`setAC`); `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:121-125` (`createUserData(Any)`), `:159-166` (`setAC`), `:131-148` (`createACClosure`).
- **Description:** `createACClosure` allocates `"__call".lua to fnRef` and `"__gc".lua to gcFnRef` (two `Pair`s), then `LuaValue.TableValue(...)` which is a `HashMap<LuaValue, LuaValue>` of size 2 — per closure. POSIX `createUserData(Any)` allocates `mapOf(LuaValue.of("__gc") to closureAutoGcFunction)` (a `LinkedHashMap`), wrapped in `LuaValue.of(...)` which calls `TableValue(HashMap(map), Nil)` — *two* HashMaps for a one-entry metatable. The Kotlin `.lua` extension on `String`/`Int`/etc. allocates a wrapper each invocation (`src/commonMain/kotlin/pw/binom/lua/LuaValueExtends.kt:88-115`), and `"__gc".lua` is recomputed for every userdata even though there is exactly one metatable key. For bulk AC userdata creation this is N×(HashMap + Pair + LuaValue.String) garbage.
- **Fix sketch:** Pre-allocate singleton metatables per engine (one `TableValue` with `__gc` → `userdataAutoGcFunction`; one with `__gc` → `closureAutoGcFunction`). Reuse for `setAC` directly. For `createACClosure`, the metatable differs per call (carries the per-closure `fnRef` and `gcFnRef`), but the *map shape* is constant — a `TableValue(LinkedHashMap<String, LuaValue>(2))` is cheaper than the current HashMap-of-LuaValue-key path. Or: introduce `LuaValue.of(vararg pairs)` overload that accepts the keys already in `String` form to skip the `.lua` boxing.

### F8. JVM `LuaNative.callbacks` map grows; no shrink path
- **Type:** Performance
- **Severity:** Info
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/LuaNative.kt:101-114` (callbacks: `ConcurrentHashMap<Int, LuaCallbackBridge>` + `nextCallbackId` AtomicInteger + `disposeCallback`).
- **Description:** `disposeCallback` removes the bridge entry when the userdata's __gc fires (or the Cleaner for the bridge runs), but `ConcurrentHashMap` does not shrink its table when entries are removed — the bucket array stays at the peak size. For an engine that registers and disposes millions of bridges (long-running service with churny Lua callback objects), the map's memory footprint is bounded by peak concurrent bridges, not live count. The `callbackCount` debug accessor returns `callbacks.size` which always reports the bucket-resident count, not the entry count.
- **Fix sketch:** No fix needed unless the engine demonstrates high churn — at which point switch to `ConcurrentHashMap` with `Mappings.tryPresize` calls on the cleanup path, or move to a slab allocator indexed by the AtomicInteger. For now, document the steady-state memory as O(peak-bridges) so callers don't expect linear release.

### F9. POSIX `ObjectContainer.add`/`remove` maintain two HashMaps and one StableRef
- **Type:** Performance, Maintainability
- **Severity:** WeakWarning
- **Location:** `src/posixMain/kotlin/pw/binom/lua/ObjectContainer.kt:7-75` (`ptrToObj` + `objToPtr`, `add`/`remove`/`clear`).
- **Description:** Every `add(data)` does: 1 `objToPtr[data]` lookup (HashMap probe), 1 `ptrToObj.put` (HashMap probe + entry alloc), 1 `objToPtr.put` (HashMap probe + entry alloc), 1 `StableRef.create` (native alloc), plus 1 CPointer extract. Every `remove(data)` mirrors that. For a script that pushes thousands of `ObjectContainer.add(...)` calls (very common for bulk data marshalling), this is 3 native allocs and 3 HashMap probes per call where a single `IdentityHashMap<Any, COpaquePointer>` plus `StableRef.create` would suffice. The dual map is only needed to support both `add(value) → ptr` and `get(LightUserData(ptr)) → value` lookups, which a single reverse map with bidirectional pointers (or `WeakHashMap` with the `COpaquePointer` as a sentinel) handles.
- **Fix sketch:** Use a single `HashMap<Any, Pair<COpaquePointer, StableRef<Any>>>` (one entry per object). `get(LightUserData)` is a linear probe keyed on `lightPtr` — keep a secondary `HashMap<COpaquePointer, Any>` only if profiling shows `get` is hot. For thread-safety, document whether the container is single-threaded (it currently is, given no `synchronized`).

### F10. JVM `ObjectContainer.ClosureMap` synchronises per get on a single global HashMap
- **Type:** Concurrency
- **Severity:** Info
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/ObjectContainer.kt:97-114` (ClosureMap synchronized methods).
- **Description:** `ClosureMap.get(id)` is invoked from inside the bridge callback (`LuaNative.invokeCallback` → `cb.invoke(ctx)` → `closuresRef[id]`). The lock is uncontended in single-threaded use but blocks every other thread making closures or invoking them. `LuaNative.callbacks` already uses `ConcurrentHashMap` for the same purpose; reusing that pattern (`ConcurrentHashMap<Int, LuaFunction>`) and dropping the wrapper class would be cheaper and concurrent.
- **Fix sketch:** Replace `ClosureMap` with `ConcurrentHashMap<Int, LuaFunction>` inline in `ObjectContainer`. Drop the `synchronized` blocks. `removeClosure`/`clear` become `remove`/`clear` calls. The `set` and `get` become `put`/`get`.

### F11. POSIX `LuaContextRegistry` uses a single global slot for (state → context)
- **Type:** Concurrency, Performance
- **Severity:** Info
- **Location:** `src/posixMain/kotlin/pw/binom/lua/LuaContext.kt:22-48` (registry: `current: Pair<LuaState, LuaContext>?`).
- **Description:** Each `register(state, ctx)` allocates a `Pair`; each `lookup(state)` reads a single field. The Pair allocation per engine creation is harmless. The comment claims "single-slot is fine", and that's true *if* engines are constructed strictly sequentially — but a second `LuaEngine()` after the first replaces `current` and the first engine's `CLOSURE_FUNCTION` calls will route through the *second* engine's `LuaContext`, misreading values. This is a correctness issue too, but as efficiency: a `HashMap<LuaState, LuaContext>` (or `CHMP`-style if available) avoids the Pair allocation and scales to N engines.
- **Fix sketch:** `private val map = HashMap<LuaState, LuaContext>()` (or `Collections.synchronizedMap`). `register(state, ctx)` = `map[state] = ctx`; `lookup(state)` = `map[state]`; `unregister(state)` = `map.remove(state)`. If multi-engine concurrency is genuinely not a target, document that with a `@Suppress` or runtime check.

### F12. JVM `pushValue` for a `TableValue` re-iterates the HashMap and pays N JNI crossings
- **Type:** Performance
- **Severity:** Info
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/LuaNative.kt:189-202` (`pushValue(statePtr, value)` for `is LuaValue.TableValue`).
- **Description:** `createTable(statePtr, 0, value.map.size)` then `for ((k, v) in value.map) { pushValue(k); pushValue(v); setTable(top) }`. Each entry pays 2 JNI for the key+value push plus 1 for `setTable`. For a 1000-key TableValue, that's 3000 JNI crossings — unavoidable for correctness but a useful ceiling to know. The `nrec` hint is correctly set to `value.map.size`. No fix needed unless bulk table marshalling becomes a bottleneck; at that point consider serializing many entries between two `lua_pushvalue`-style checkpoints or batching into `lua_settable`-equivalent via a single JNI helper.
- **Fix sketch:** None unless profiling shows it. If it does, expose a `pushTableBatch(statePtr, Array<Pair<LuaValue, LuaValue>>)` JNI helper that holds the stack in a `PushLocalFrame`/`PopLocalFrame` pair and avoids the per-entry native crossing by pinning the JNIEnv once.

### F13. POSIX `readValue` for `LUA_TFUNCTION` with `ref=false` loops `lua_getupvalue` to count
- **Type:** Performance
- **Severity:** WeakWarning
- **Location:** `src/posixMain/kotlin/pw/binom/lua/LuaValueReader.kt:42-66` (the `LUA_TFUNCTION` branch, upvalue-count loop and read).
- **Description:** For `ref=false`, `readValue` calls `lua_getupvalue(state, index, upvalueCount + 1)` in a probe loop until it returns null, then reads each upvalue with another `lua_getupvalue` per index. For a function with N upvalues, that's `2N+1` C calls (probe + read + pop). Lua C API exposes `lua_getupvalue` only one index at a time; there is no batched form. The probe loop is the cheapest correct way, but note this is called from every function-ref `toValue()` on POSIX (and JVM equivalent uses `lua_topointer` once + reads only the closure object — no per-upvalue traversal). 
- **Fix sketch:** Cache the upvalue-count and upvalues at `FunctionRef` construction time so `toValue()` becomes O(1). Alternatively, document that `toValue()` on a function-ref is expensive and discourage its use on hot paths.

---

## FINDINGS COUNT
13

## SUMMARY
Top wins: (1) `StaticRefs` global monitor is the JVM-side multi-thread bottleneck — single biggest concurrency/GC issue; (2) `readValue`/`absIndex` JNI overhead on every value round-trip; (3) `UserData.ptr` 3-JNI recomputation per property read; (4) POSIX `checkState` doubles stack-check cost on every TableRef op; (5) Cleaner registration per Lua-side wrapper read. Lower-impact cleanups: precomputed metatables for `setAC`/`createACClosure`, drop the dual HashMap in POSIX `ObjectContainer`, switch `ClosureMap` to `ConcurrentHashMap`, cache `UserData.ptr` lazily. None of these are correctness bugs; all are throughput/GC wins.
