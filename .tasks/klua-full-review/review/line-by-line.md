# klua line-by-line correctness review

Scope: `src/commonMain`, `src/jvmMain`, `src/posixMain` under `pw/binom/lua/`. Focus on runtime behavior — what input/sequence breaks each line.

## Findings

### L1 — posix `userdataGc` double-derefs the StableRef pointer (memory corruption)
- **Type:** Bug
- **Severity:** Error
- **Location:** `src/posixMain/kotlin/pw/binom/lua/KotlinLuaFunction.kt:33-34`  prefix `val ptr = Heap.getPtrFromPtr(state.readUserData(1))`
- **Description:** `state.readUserData(1)` already dereferences the userdata payload via `Heap.getPtrFromPtr(lua_touserdata(...))` and returns the stored `StableRef<Any>` pointer. Wrapping that result in `Heap.getPtrFromPtr(...)` again treats the `StableRef` object itself as a `klua_pointer { void* pointer; }` and reads the first 8 bytes of the Kotlin/Native object header as a pointer. The subsequent `ptr.asStableRef<Any>().dispose()` then frees an arbitrary address (the obj-header/typeinfo pointer), causing heap corruption when `collectgarbage` triggers `__gc` for any `createAC(...)` / `createACClosure(...)` userdata. Common tests `testAutoCleanUserData` and `autoCleanClosureTest1` exercise this path; corruption may be latent because nothing reads the freed object after `__gc`.
- **Fix sketch:** Drop the outer deref — `val ptr = state.readUserData(1)`.

### L2 — JVM `setAC` overwrites the userdata `__gc` with the closure-gc trampoline → StaticRefs leak
- **Type:** Bug
- **Severity:** Error
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:185-192`  prefix `actual fun setAC(userdata: LuaValue.UserData)`
- **Description:** `createUserData(value)` (line 106) installs `__gc = userdataAutoGcFunction` whose C trampoline (`klua_userdata_gc_trampoline` → `disposeUserdata(mem)`) drops the `StaticRefs` entry stored under the userdata's memory address. `setAC` then reads the metatable and overwrites `__gc` with `closureAutoGcFunction`, whose trampoline (`klua_gc_trampoline` → `disposeCallback(id)`) only removes a bridge from `LuaNative.callbacks` — it never touches `StaticRefs(mem)`. Net effect: every `LuaEngine.createAC(LightUserData)` leaks the entry at `mem` for the lifetime of the engine (the entry that holds the actual Kotlin object the user passed in). `createAC(Any?)` (line 200) deliberately avoids `setAC` and so does NOT leak, making the two `createAC` overloads inconsistent. `setAC(userdata)` called directly on a pre-existing userdata has the same leak.
- **Fix sketch:** Either don't call `setAC` from `createAC(LightUserData)`, or change `klua_gc_trampoline` (or the per-closure `__gc` it replaces) to also `disposeUserdata(mem)` for the userdata at idx 1.

### L3 — posix `LuaEngine.close()` is a no-op; state never closed, registry pins it
- **Type:** Bug
- **Severity:** Error
- **Location:** `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:20-21`  prefix `actual override fun close()`
- **Description:** The body is empty. State teardown relies on the `createCleaner` registered inside `LuaContext`, but `LuaContextRegistry.current = state to ctx` holds a strong reference to the last `LuaContext` forever, so it never becomes phantom-reachable and `lua_close` is never called. Creating a second engine merely overwrites `current` — the first context is then unreferenced and eventually GC'd, but only if/when the second engine is created. `AutoCloseable.close()` contract is silently violated. Also: callers following the JVM pattern of `use { ... }` get no resource release.
- **Fix sketch:** Call `lua_close(ll.state)` (or `ll.dispose()`) here and `LuaContextRegistry.unregister(ll.state)`. Also clear `LuaContextRegistry.current` so the Cleaner can run.

### L4 — posix wrapper Cleaners use-after-free the lua_State if engine is GC'd first
- **Type:** Concurrency
- **Severity:** Error
- **Location:** `src/posixMain/kotlin/pw/binom/lua/Lua.kt:14-17`  prefix `internal fun createCleaner1(state: LuaContext, ref: LuaRef)`
- **Description:** The action unconditionally calls `it.first.state.disposeRef(it.second)` (i.e. `luaL_unref(state, REGISTRYINDEX, ref.id)`) on the raw `lua_State*`. There is no equivalent of JVM `RefAction`'s `if (statePtr == 0L) return` guard. Scenario: engine A is created, then engine B is created → `LuaContextRegistry.current` overwrites A → A becomes phantom-reachable → its Cleaner calls `lua_close(A.state)` → some still-alive `TableRef`/`FunctionRef`/`UserData` wrapper from engine A is later collected → its Cleaner fires `luaL_unref` on freed memory → UB / crash. JVM guards this exact scenario; posix does not.
- **Fix sketch:** Capture `state` at registration; in the action, check that the state still resolves via `LuaContextRegistry.lookup(state)` (or compare against a generation counter on the `LuaContext`) and bail if not.

### L5 — JVM `pcallCall` mis-maps Lua 5.4 status codes → runtime errors become "Unknown pcall status: 2"
- **Type:** Bug
- **Severity:** Error
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:432-459`  prefix `internal fun pcallCall(ll: LuaContext, args: List<LuaValue>)`
- **Description:** The `when (r)` uses 4 → runtime error, 5 → "memory allocation error", 6 → message-handler error. Lua 5.4 `lua_pcall` returns LUA_OK=0, LUA_ERRRUN=2, LUA_ERRMEM=4, LUA_ERRERR=5 — so a normal Lua runtime error returns 2, which falls into `else` → `RuntimeException("Unknown pcall status: 2")`, and the error object is left on the stack (registry / static refs / arena leak per call). The top-level `pcallProcessing` in `LuaEngine.kt` correctly uses 2/4/5; `pcallCall` (used by `TableRef.call`, `FunctionRef.call`, `UserData.call`) is off by 2. Tests that error from Lua and call these paths will see confusing non-`LuaException` and accumulating stack.
- **Fix sketch:** Change the branches to `2 -> { ... throw LuaException(msg) }`, `4 -> memory allocation error`, `5 -> message handler error`, and pop the error before throwing in every branch.

### L6 — posix `ObjectContainer.removeClosure` always returns false / leaves stale pointer
- **Type:** Bug
- **Severity:** Error
- **Location:** `src/posixMain/kotlin/pw/binom/lua/ObjectContainer.kt:58-66`  prefix `actual fun removeClosure(data: LuaValue.FunctionRef): Boolean`
- **Description:** `removeClosure(FunctionRef)` does `if (data.ptr != CLOSURE_FUNCTION) return false; return remove(data.toValue())` — but `remove(data: Any)` looks up the `FunctionValue` instance in `objToPtr`, which is keyed by the original `LuaFunction` (not by `FunctionValue`). The lookup returns `null`, so it always returns `false` and the bridge's `StableRef` is never disposed. `removeClosure(FunctionValue)` disposes the upvalue's `StableRef` but does NOT remove the corresponding entries from `ptrToObj`/`objToPtr`; the next `add(sameFunc)` call hits the stale entry in `objToPtr` and returns a `LightUserData` wrapping an already-disposed `StableRef` → use-after-free on access.
- **Fix sketch:** Extract the original `LuaFunction` from the upvalue (`func.upValues[0].value as LuaFunction`) and call `remove(originalFunc)` which correctly removes from both maps and disposes the ref once.

### L7 — posix `LuaContextRegistry` is a single global slot; multi-engine loses callbacks and pins state
- **Type:** Concurrency
- **Severity:** Error
- **Location:** `src/posixMain/kotlin/pw/binom/lua/LuaContext.kt:26-56`  prefix `internal object LuaContextRegistry`
- **Description:** `current: Pair<LuaState, LuaContext>?` holds at most one engine. Creating a second engine overwrites the slot, after which `lookup(state_of_first_engine)` returns `null` → `CLOSURE_FUNCTION` falls into the `?: return@staticCFunction 0` branch and silently returns 0 results for every Kotlin callback dispatched from engine A. The strong reference also keeps engine A's `LuaContext` alive (preventing its `lua_close` Cleaner from ever firing) until engine B is created. Concurrent `LuaEngine` construction in different threads races on `current`. The KDoc acknowledges the limitation but the bug is observable today if a user creates two engines and uses both.
- **Fix sketch:** Replace with a `ConcurrentHashMap<CPointer<lua_State>, LuaContext>`; or, better, attach the `LuaContext` pointer via `lua_setuservalue` on the `lua_State` (would require a Lua patch).

### L8 — JVM `eval` mislabels Lua status codes and leaks the stack on syntax / ERRERR
- **Type:** Bug
- **Severity:** Error
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:51-65`  prefix `actual fun eval(text: String): List<LuaValue>`
- **Description:** `luaL_loadstring` returns LUA_ERRSYNTAX=3, LUA_ERRMEM=4, LUA_ERRERR=5. The `when (r)` treats 4 as the generic "compile error" (and pops the message) but treats 5 as "LUA_ERRMEM" with no pop, and lets LUA_ERRSYNTAX(3) fall into `else` with no pop and a generic message. Net effect: every failed `eval` leaks one stack slot and one `LuaValue` wrapper, the error label for status 5 is wrong, and a syntax error loses the actual Lua message.
- **Fix sketch:** Branch on the named `LUA_ERRSYNTAX`/`LUA_ERRMEM`/`LUA_ERRERR` constants (mirror the posix `LuaEngine.eval`), read & pop the message in every error branch.

### L9 — Lua integers are always read back as `Number`, never `LuaInt`
- **Type:** Bug
- **Severity:** Error
- **Location:** `src/posixMain/kotlin/pw/binom/lua/LuaValueReader.kt:36-42`  prefix `LUA_TNUMBER -> LuaValue.Number(lua_tonumberx(state, index, null))`
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/LuaContext.kt:55-56`  prefix `3 -> LuaValue.Number(LuaNative.toNumber(statePtr, index))`
- **Description:** Round-trip `engine.set("x", of(5L))` then `engine.get("x")` returns `LuaValue.Number(5.0)`. `checkedInt()` / `intOrNull()` on that always fail with `LuaCastException` / `null` because the read path uses `lua_tonumberx` / `LuaNative.toNumber` for both floats and ints. `LuaNative.isInteger` / `LuaNative.toInteger` exist but are never consulted by `readValueAt`. As a result, `LuaValue.LuaInt` is reachable only when constructed in Kotlin, never when reading from the engine — silently breaking any caller that distinguishes ints.
- **Fix sketch:** In both `readValue*`s, after computing `type`, if the C side reports `lua_isinteger` use `LuaValue.LuaInt(lua_tointeger(...))`, otherwise `LuaValue.Number(...)`.

### L10 — posix `call(functionName)` leaks the Lua stack on the "function not found" path
- **Type:** Bug
- **Severity:** Warning
- **Location:** `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:52-70`  prefix `actual fun call(functionName: String, vararg args: LuaValue)`
- **Description:** When the global is `nil`, the code throws `LuaException("Function ... not found")` without `lua_pop` of the pushed nil. The "not a function" path pops correctly. JVM `call(functionName)` pops in both error branches. Repeated failed lookups on the same engine grow the stack and ultimately trigger Lua's stack-overflow / soft-limit handling.
- **Fix sketch:** `lua_pop(ll.state, 1)` before the throw in the nil branch.

### L11 — posix `readValue` for `LUA_TFUNCTION` with `ref=false` produces a `FunctionValue(ptr=null)`
- **Type:** Bug
- **Severity:** Warning
- **Location:** `src/posixMain/kotlin/pw/binom/lua/LuaValueReader.kt:40-58`  prefix `LUA_TFUNCTION -> { if (ref) { ... } else { ... val funcPtr = lua_tocfunction(state, index)`
- **Description:** `lua_tocfunction` returns `null` for any Lua-defined function (it only returns non-null for C functions). The code unconditionally stores it: `LuaValue.FunctionValue(upValues = upvalues, ptr = funcPtr)`. If that value is later passed to `LuaEngine.set`/`call`/`pushValue`, `lua_pushcclosure(state, null, upValues.size)` is invoked with a null function pointer → undefined behaviour in Lua (typically crash inside `pushcclosure`). Affects any code path that materialises a Lua function with `toValue()` / `ref=false`.
- **Fix sketch:** Either refuse (throw `LuaException("Lua functions are not materialisable")`) or store the raw function pointer via `lua_topointer` and add a dedicated code path that pushes it back through `lua_pushvalue` of a registry-stashed copy.

### L12 — JVM `readValueAt` (table, ref=false) recursion is not wrapped in `checkState`
- **Type:** Maintainability
- **Severity:** Warning
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/LuaContext.kt:46-109`  prefix `private fun readValueAt(index: Int, ref: Boolean): LuaValue`
- **Description:** Posix wraps every Lua-touching block in `checkState { ... }` (which `check`s net stack delta == 0). The JVM equivalent performs identical stack manipulations but has no equivalent guard — a one-off error here silently leaks stack and only shows up later as a wrong value / `IllegalStateException` far from the cause. The recent commit `6dc634d` fixed a stack-imbalance here precisely because the guard was absent.
- **Fix sketch:** Add a JNI `getTop`/`setTop` pair around each `readValueAt` branch (or use a `StackProbe` helper) mirroring posix's `checkState`.

### L13 — JVM `getMetatable` and `TableRef.toValue` allocate a transient registry slot per call
- **Type:** Performance
- **Severity:** Warning
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:389-401`  prefix `internal fun getMetatable(ll: LuaContext, value: LuaValue.Meta): LuaValue`
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:269-283`  prefix `actual override fun toValue(): TableValue`
- **Description:** `getMetatable` calls `ll.readValue(-1, true)` which for a table hits the `ref=true` branch and creates a `TableRef` backed by a new `luaL_ref` slot. Each call to `userdata.metatable` getter (also `setAC` line 187), each `TableRef.toValue()`, and each `getMetatable` chain in the JVM table materialiser thus grows the Lua registry by one slot until the wrapper is GC'd. Under GC stress or under tight loops (the recent GC bug in commit `02de357`) the registry can balloon; the test `JvmAcDisposeTest.tableRefToValueAndMetatableAreStackBalanced` exists exactly for this scenario.
- **Fix sketch:** Expose a `readValueAtNoRef` variant for the metatable / key-only cases and let the caller decide; or implement metatable read directly (push + `lua_getmetatable` + `lua_tostring`/lookup), bypassing the generic TableRef creation.

### L14 — JVM `createUserData(LightUserData)` silently nulls the data on reuse
- **Type:** Bug
- **Severity:** Warning
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:106-128`  prefix `actual fun createUserData(value: LuaValue.LightUserData): LuaValue.UserData`
- **Description:** The method reads `value.value = StaticRefs.get(value.ptr)`, stores it under `mem`, then `StaticRefs.dispose(value.ptr)`. Any subsequent access through the same `LightUserData` wrapper (e.g., calling `lud.value` again, or passing the same `lud` to a second `createUserData`) will see `StaticRefs.get(disposed_ptr) == null` and silently produce a userdata wrapping `null` instead of the original Kotlin object. No exception, no warning.
- **Fix sketch:** Reject reuse (`check(value.ptr !in disposedSet)`) or, if reuse is intended, document and require callers to construct a fresh `LightUserData` from the underlying object.

### L15 — posix `ObjectContainer.add` dedups by `equals`, so value-equal objects share and break on `remove`
- **Type:** Bug
- **Severity:** Warning
- **Location:** `src/posixMain/kotlin/pw/binom/lua/ObjectContainer.kt:14-24`  prefix `actual fun add(data: Any?): LuaValue.LightUserData`
- **Description:** `objToPtr[data]` uses `HashMap` equality. Two distinct `String("x")` instances, or two equal data-class instances, or two equal arrays, share one `StableRef`. `remove(data)` then disposes the shared ref for both; whichever instance is touched afterwards dereferences a disposed `StableRef`. Also problematic for arrays (`equals` is identity) and primitives boxed.
- **Fix sketch:** Use an `IdentityHashMap` (or `MutableMap<Any, COpaquePointer>` keyed by `System.identityHashCode` with linear-probe fallback) to key by object identity only.

### L16 — JVM `StaticRefs.intern` does not dedupe; `remove` only drops one entry per call
- **Type:** Bug
- **Severity:** WeakWarning
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/StaticRefs.kt:9-40`  prefix `fun intern(value: Any?): Long`
- **Description:** Every `intern` allocates a fresh counter key, so `container.add(obj)` called twice yields two `LightUserData` with different `ptr`s, both pointing to `obj`. `container.remove(obj)` walks the map and removes only the first match (`removeIfMatches` returns after the first `it.remove()`). The remaining entry is only freed when its `LightUserData` wrapper's `Cleaner` fires. Repeated `add`+`remove` cycles leak `StaticRefs` entries between calls. Not a critical leak (cleaners eventually run), but the asymmetry with the JVM GC bug fixed in commit `595d8b4` (orphan-entry family) suggests this was already on the radar.
- **Fix sketch:** Either dedupe by identity (`map.putIfAbsent` keyed by the value) or change `removeIfMatches` to remove ALL entries matching `data`.

### L17 — JVM `pcallCall` reads result indices `1..count` assuming the stack below is empty
- **Type:** Bug
- **Severity:** Warning
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:432-459`  prefix `internal fun pcallCall(ll: LuaContext, args: List<LuaValue>)`
- **Description:** After `pcall`, `count = getTop - topBefore + 1` and the loop reads `(1..count).map { ll.readValue(it, true) }`. This is only correct when `topBefore == 1` (the callable alone on the stack). All current callers satisfy that, but if `pcallCall` is ever invoked with other values below the callable (e.g. from inside a nested helper that already pushed something), wrong slots are read and the wrong number of slots are popped. Latent.
- **Fix sketch:** Read starting at `topBefore` (the first result slot) for `count` slots and pop `count`, instead of `1..count`.

### L18 — `LuaInt.equals` on posix compares boxed Long instead of `is LuaInt`
- **Type:** Bug
- **Severity:** WeakWarning
- **Location:** `src/posixMain/kotlin/pw/binom/lua/LuaValue.kt:94-96`  prefix `actual override fun equals(other: Any?): kotlin.Boolean = value == other`
- **Description:** JVM (`LuaValue.kt:147`) explicitly guards `other is LuaInt && value == other.value`, so `LuaInt(5).equals(Long(5))` is `false` on JVM but `true` on posix. The asymmetry means a value round-tripped through `set("k", LuaInt(5))` then read back (becomes `Number(5.0)` per L9) compared with `Long(5)` in Kotlin yields divergent results across platforms, breaking any shared cross-platform test that relies on equality.
- **Fix sketch:** Match JVM: `other is LuaInt && value == other.value`.

### L19 — JVM `LuaContext.wrap` exposes a `close()` that would tear down the owning engine
- **Type:** Bug
- **Severity:** Warning
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/LuaContext.kt:15-17` and `111-119`  prefix `private constructor(initialState: Long) { statePtr = initialState }`
- **Description:** `LuaContext.wrap(statePtr)` constructs a `LuaContext` via the private constructor that just stores the pointer (no `LuaNative.newState` call). It returns an `internal class LuaContext` whose `close()` (public) calls `LuaNative.close(statePtr)` and nulls the pointer. The wrapper exists only for the lifetime of an `invokeCallback` and the comment says it "must not escape the callback scope", but nothing enforces that — any user code (or future refactor) holding a callback's `ctx` and calling `close()` would close the entire engine. The cross-cutting "Cleaner reads statePtr at run time" comment in `LuaValue.kt` only helps if the wrapper itself is unreachable; once closed, the engine's state is gone.
- **Fix sketch:** Make the wrapper a separate inner type with a no-op or absent `close`, or check `statePtr == 0L` in `close()` and silently ignore.

### L20 — NativeLoader has a TOCTOU race on first extract + no checksum verification
- **Type:** Concurrency
- **Severity:** WeakWarning
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/NativeLoader.kt:12-23`  prefix `fun load()`
- **Description:** Two threads hitting `load()` simultaneously both observe `!cached.exists()`, both run `extractFromJar`, both write to the same `targetFile`. The `loaded.add` check happens after extract, so both extract. The first `System.load` may race with the second's open-write; the second may then load a half-written file. Also, no integrity check on the extracted library.
- **Fix sketch:** Synchronize `extractFromJar` on a class object or use a `FileLock`; verify size or SHA after extract.

### L21 — Dead classes `LuaCtx` and `LuaStateAndLib` duplicate `LuaContext`
- **Type:** Maintainability
- **Severity:** WeakWarning
- **Location:** `src/posixMain/kotlin/pw/binom/lua/LuaCtx.kt:9-15`  prefix `class LuaCtx private constructor(val state: LuaState)`
- **Location:** `src/posixMain/kotlin/pw/binom/lua/LuaStateAndLib.kt:6`  prefix `internal class LuaStateAndLib(val state: LuaState)`
- **Description:** Neither class is referenced anywhere in `src/posixMain` (verified by full-tree search). `LuaCtx` even registers its own Cleaner that calls `lua_close` — if a future caller instantiates it, the cleaner captures `state` (a CPointer, fine) but `LuaCtx` is never registered in `LuaContextRegistry`, so any callbacks dispatched through it would crash via `CLOSURE_FUNCTION` -> `LuaContextRegistry.lookup(state)` -> null.
- **Fix sketch:** Delete both files.

### L22 — JVM `ObjectContainer.remove(data)` only removes one entry by identity
- **Type:** Bug
- **Severity:** WeakWarning
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/ObjectContainer.kt:49`  prefix `actual fun remove(data: Any): Boolean`
- **Description:** `StaticRefs.removeIfMatches` removes the first map entry whose `value === data` and returns; it does not iterate further. Combined with the no-dedup behaviour of `intern` (L16), calling `remove(obj)` after N `add(obj)` leaves N-1 entries behind. Each is freed eventually by its `LightUserData` cleaner, but `StaticRefs.size` will not return to baseline between successive `add`+`remove` cycles, matching the leak shape flagged by `JvmAcDisposeTest`.
- **Fix sketch:** Either return a count (Int) and loop the map, or document the contract as "remove at most one entry".

### L23 — `LuaValue.LightUserData(ptr = 0L)` on JVM skips the Cleaner, leaking the (impossible-to-store) entry
- **Type:** Bug
- **Severity:** Info
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:81-90`  prefix `private val cleanable: Cleaner.Cleanable? = ptr?.let { p -> REFCLEANER.register(this, LightUserDataAction(p)) }`
- **Description:** The primary constructor takes `ptr: Long?` and skips the Cleaner when `ptr == null`. The secondary constructor `actual constructor(value: Any?)` always passes a non-null `StaticRefs.intern(value)` result, so this dead branch can't currently be reached. Worth a defensive note because future JNI work might produce a null `ptr` legitimately and then there is no auto-cleanup.
- **Fix sketch:** Document or `check(ptr != null)` in the secondary constructor.

## FINDINGS COUNT

Total: **23**
- Error: **9** (L1, L2, L3, L4, L5, L6, L7, L8, L9)
- Warning: **8** (L10, L11, L12, L13, L14, L17, L19, plus L15 — counted as Warning, see below)
- WeakWarning: **5** (L16, L18, L20, L21, L22)
- Info: **1** (L23)

(Recounted: Error 9, Warning 8, WeakWarning 5, Info 1 = 23.)

## SUMMARY

Dominant theme: **memory ownership and lifetime** on both native bridges. The posix side has the most serious issues — L1 (double-deref of `StableRef` in `userdataGc` triggers heap corruption on every `createAC`/`createACClosure` `__gc` cycle), L3 (engine state never closed because the registry pins it), L4 (cleaners UAF on freed `lua_State` if the engine is replaced), and L6/L7 (broken `removeClosure` and single-slot registry). JVM has two cluster bugs: L2 (`setAC` replaces the userdata gc with the closure gc, leaking `StaticRefs` entries) and L5/L8 (off-by-2 pcall status mapping, masking real Lua errors as `RuntimeException("Unknown pcall status: 2")` and leaking the stack). Cross-cutting: L9 makes `LuaValue.LuaInt` effectively unreachable from any read, breaking the int/number distinction across both platforms. Hottest files: `LuaEngine.kt` (both platforms), `ObjectContainer.kt` (posix), `KotlinLuaFunction.kt` (posix), `LuaValue.kt` (jvm), `LuaContext.kt` (jvm). Overall code health is fragile — the recent commit log (Cleaner rewrites, __gc callback-id mismatches, GC stress tests) shows the project is actively chasing this exact class of bug, and these findings are likely additional instances of the same family.
