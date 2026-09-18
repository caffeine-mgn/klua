# Cross-file tracer review — klua

Tracing call chains across commonMain / jvmMain / posixMain (and the C
side `src/jvmMain/c/klua_jni.c` / `src/nativeMain/lua/lua.h` for ground
truth). Every finding below names both sides, the precise line ranges,
and a concrete failure observed.

---

## Findings

### 1. JVM `pcallCall` dispatches Lua error codes off by 2; throws `RuntimeException` instead of `LuaException` for the common runtime-error path

- **Type:** Bug
- **Severity:** Error
- **Location:**
  - `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:440-457` (`pcallCall`)
  - `src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:213-236` (`pcallProcessing`, correct)
  - `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:195-225` (POSIX `pcallProcessing`, correct)
  - Ground truth: `src/nativeMain/lua/lua.h:51-55` — `LUA_ERRRUN=2`, `LUA_ERRMEM=4`, `LUA_ERRERR=5`.
  - `src/jvmMain/c/klua_jni.c:570-573` — `Java_pw_binom_lua_LuaNative_pcall` returns the raw `lua_pcallk` return code unchanged.
- **Description:**
  Lua 5.4 status codes are defined in `lua.h` as `LUA_OK=0`, `LUA_ERRRUN=2`, `LUA_ERRMEM=4`, `LUA_ERRERR=5`. The JNI `pcall` returns the raw code. JVM `LuaEngine.pcallProcessing` handles `2 → LuaException(msg)` (correct). JVM `LuaValue.pcallCall`, however, is dispatched by `TableRef.call`, `FunctionRef.call`, and `UserData.call` (`LuaValue.kt:56, 269, 310`) and its `when (r)` is `4, 5, 6` — i.e. shifted by 2 with respect to the actual codes:
  ```kotlin
  4 -> { val msg = LuaNative.toString(ll.state, -1) ?: "runtime error"
         LuaNative.pop(ll.state, 1)
         throw LuaException(msg) }
  5 -> throw RuntimeException("memory allocation error")
  6 -> throw RuntimeException("error while running the message handler")
  else -> throw RuntimeException("Unknown pcall status: $r")
  ```
  Trace:
  - User calls `f.call(arg)` on a `FunctionRef` whose underlying Lua function does `error("boom")` (returns `LUA_ERRRUN=2`).
  - Status `2` is unhandled → falls to `else` → throws `RuntimeException("Unknown pcall status: 2")`.
  - The same scenario on POSIX (`FunctionRef.call → ll.callClosure → pcallProcessing`) correctly throws `LuaException("boom")` via the `LUA_ERRRUN` arm.
  - `LuaEngine.call(functionName, ...)` on JVM is fine (uses `pcallProcessing`).
  - Net: the same runtime error inside Lua produces an untyped `RuntimeException` with no message via `FunctionRef`/`TableRef`/`UserData`, but a typed `LuaException` with the message via `LuaEngine.call`. Callers cannot `catch (e: LuaException)` uniformly, and the actual error text is lost.
- **Fix sketch:**
  Change `pcallCall`'s dispatch to match `pcallProcessing` in `LuaEngine.kt` (using `LUA_ERRRUN=2 → LuaException`, `LUA_ERRMEM=4`, `LUA_ERRERR=5`):
  ```kotlin
  when (r) {
      0 -> { /* ok path */ }
      2 -> { val msg = LuaNative.toString(ll.state, -1); LuaNative.pop(ll.state, 1); throw LuaException(msg ?: "<lua error>") }
      4 -> { LuaNative.pop(ll.state, 1); throw RuntimeException("memory allocation error") }
      5 -> { throw RuntimeException("error while running the message handler") }
      else -> throw RuntimeException("Unknown pcall status: $r")
  }
  ```
  Even better — extract the shared dispatch into a single helper used by both `pcallProcessing` and `pcallCall` (and the POSIX side) so the two JVM handlers and POSIX can't drift again.

---

### 2. `LuaEngine.close()` is a contract no-op on POSIX but releases the native state on JVM

- **Type:** Bug (contract violation)
- **Severity:** Error
- **Location:**
  - `src/commonMain/kotlin/pw/binom/lua/LuaEngine.kt:9` — `expect ... override fun close()`
  - `src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:36-38` — `actual override fun close() { ll.close() }` (closes the lua_State and nulls `statePtr`)
  - `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:17` — `actual override fun close() { }` (empty body)
  - `src/posixMain/kotlin/pw/binom/lua/LuaContext.kt:20-22` — the actual teardown happens in a `createCleaner(state)` block, only on GC.
- **Description:**
  `AutoCloseable.close()` is documented to release resources deterministically. JVM honours that (`ll.close()` → `LuaNative.close(state)` + nulls the cached pointer so Cleaner actions become no-ops — see `LuaContext.kt:106-117` and the `RefAction.run`/`UserDataAction.run` guards at `LuaValue.kt:80-99, 335-345`). POSIX does not: the override is empty and the state survives until the `LuaContext` wrapper itself becomes phantom-reachable. Practically:
  - POSIX: after `engine.close()` the engine is **still usable** — `engine.eval(...)`, `engine.get(...)`, `engine.set(...)`, `engine.call(...)` all keep working, throwing nothing. The ref-id slots registered with `luaL_ref` in the registry are still alive and will keep the underlying Lua tables/functions/closures pinned, defeating the deterministic cleanup pattern callers expect.
  - POSIX: a `lua_State` global lives in `LuaContextRegistry.current` (`LuaContext.kt:47`) and is only cleared when the `LuaContext` is GC'd, not by `close()`. So creating a second engine on POSIX before the first is GC'd overwrites the single registry slot — `CLOSURE_FUNCTION` and `userdataGc` callbacks on the older state can no longer find their `LuaContext` (`KotlinLuaFunction.kt:67-71`) and silently no-op.
  - JVM: after `close()` any further call through `Ll` dereferences a 0 `statePtr` — but `LuaEngine.close()` is also not idempotent on JVM (re-calling doesn't hurt, but operations on a closed engine are silent undefined behaviour from the user's point of view).
- **Fix sketch:**
  POSIX `actual override fun close()` should mirror JVM: stash the `LuaState`, run `lua_close(state)` (and unregister from `LuaContextRegistry`) synchronously, then null the cached state. If an explicit "don't double-close" guard is wanted, set the cached `state` field to `null` after closing (matching the JVM pattern). The Cleaner remains as a safety net against holders that forget `close()`.

---

### 3. POSIX `LuaEngine.call(functionName, ...)` leaks one stack frame on the "function not found" error path

- **Type:** Bug
- **Severity:** Error
- **Location:**
  - `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:55-69` — POSIX `call(functionName, ...)` path
  - `src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:71-83` — JVM counterpart (which correctly pops)
- **Description:**
  ```kotlin
  // posix
  lua_getglobal(ll.state, functionName)
  if (lua_isnil1(ll.state, -1)) {
      throw LuaException("Function \"$functionName\" not found")     // ← no lua_pop
  }
  if (!lua_isfunction1(ll.state, -1)) {
      lua_pop(ll.state, 1)                                          // ← balanced
      throw LuaException("\"$functionName\" is not a function")
  }
  ```
  Trace: `lua_getglobal` pushes the looked-up value at `-1`. The `isNil` arm throws without popping, so the cached nil is left on the stack. After 1000 failed lookups the stack has 1000 extra frames. JVM pops both branches. Also, because POSIX wraps every other primitive with `lua.state.checkState { ... }`, every successful call asserts top-at-entry equals top-at-exit — this is the place where `checkState` should be applied but is not (POSIX's `call()` is wrapped in nothing, while its sibling `get(name)` is wrapped: `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:21-25`).
- **Fix sketch:**
  Add `lua_pop(ll.state, 1)` before `throw` in the nil branch, and wrap the whole method body in `ll.state.checkState { ... }` like the other accesses.

---

### 4. POSIX `createUserData(LightUserData)` leaves the userdata without a `__gc` metamethod; ownership of the wrapped StableRef is silently lost

- **Type:** Bug / Contract mismatch
- **Severity:** Error
- **Location:**
  - `src/commonMain/kotlin/pw/binom/lua/LuaEngine.kt:43-46` — `expect fun createUserData(value: LuaValue.LightUserData)`
  - `src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:121-135` — JVM installs `__gc` (`userdataAutoGcFunction`) and disposes `value.ptr` so ownership transfers to the new userdata.
  - `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:107-114` — POSIX never installs a metatable and never disposes the `LightUserData`. The function returns early.
- **Description:**
  Both impls declare the same signature in `expect`, but their semantics differ:
  - JVM (`LuaEngine.kt:121-135`): allocates the lua userdata, stores the LightUserData's underlying value via `StaticRefs.store(mem, underlying)`, **disposes `value.ptr`** to drop the original StaticRefs slot, then installs `__gc = userdataAutoGcFunction` so Lua-side GC drops the new slot. Ownership transfers cleanly.
  - POSIX (`LuaEngine.kt:107-114`): simply `lua_newuserdata1` + `Heap.setPtrFromPtr(mem, value.lightPtr)`, **no metatable**, **no cleanup of the underlying `value.lightPtr`** (a StableRef the LightUserData holds). When the returned userdata is collected by Lua, nothing happens; when the userdata *isn't* collected (long-lived reference passed to a kept global), the StableRef lives for the program's lifetime — but the matching `__gc` is needed precisely to bound it.
  - The call site `createAC(value: LightUserData)` on POSIX (`LuaEngine.kt:166-170`) compensates by calling `setAC` right after, but any code that reaches `createUserData(LightUserData)` outside `createAC` (e.g., a custom subclass calling the expect API) gets a leaked StableRef on POSIX. On JVM, `createUserData(LightUserData)` self-cleans because the cleanup is built in.
  - Concretely: on POSIX today, `createAC(value: Any?)` (line 173-180) goes `createAC(LightUserData(ref))` → `createUserData(LightUserData(ref))` which leaves the userdata without `__gc` for an instant, then `setAC` patches it. The window where `__gc` is missing is only as wide as the `setAC` call, but the docs in `commonMain` make no such promise, and `createUserData(LightUserData)` as a public API is unsafe on POSIX.
- **Fix sketch:**
  In `posixMain/.../LuaEngine.kt:createUserData(LightUserData)`, after constructing the userdata, install `metatable = TableValue("__gc".lua to closureAutoGcFunction)` (mirror what `createUserData(Any)` does at line 117-123) — and consider invoking `value.dispose()` so the StableRef is owned by the payload slot, not the transient `LightUserData` Kotlin wrapper.

---

### 5. `userdataAutoGcFunction` is dead-on-arrival on POSIX; declared in `expect`, initialized once, never consumed internally

- **Type:** Design / Public Surface Inconsistency
- **Severity:** Warning
- **Location:**
  - `src/commonMain/kotlin/pw/binom/lua/LuaEngine.kt:6-7` — `val closureAutoGcFunction: LuaValue.FunctionRef` and `val userdataAutoGcFunction: LuaValue.FunctionRef`
  - `src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:7-8, 17-29, 121-140, 188-190` — JVM uses `userdataAutoGcFunction` as the metatable `__gc` in `createUserData` (lines 121-135 and 121-140 in the same file).
  - `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:12-14` — POSIX initialises both `closureAutoGcFunction` and `userdataAutoGcFunction` to the same `makeRef(FunctionValue(userdataGc))`. Grep confirms `userdataAutoGcFunction` is then never referenced anywhere in `posixMain/`.
- **Description:**
  - The `expect` API exposes *two* GC functions with implied distinct semantics (`closureAutoGcFunction` for closures owned via `__call` userdata; `userdataAutoGcFunction` for plain userdata payloads). POSIX collapses both onto the same cfunction (`userdataGc`), so callers who install `engine.userdataAutoGcFunction` as `__gc` get the closure-tail semantics, not payload semantics.
  - More importantly: the *payload contract* of the two is genuinely different:
    - JVM `userdataAutoGcFunction` (via `pushUserdataGcFunction` → `klua_userdata_gc_trampoline`): reads idx 1's `lua_touserdata` block (the `mem` address returned by `LuaNative.newUserdata`), forwards that address to `disposeUserdata(mem) → StaticRefs.dispose(mem)`. Trampoline code at `src/jvmMain/c/klua_jni.c:201-225`.
    - POSIX `userdataAutoGcFunction` (=`userdataGc`, `src/posixMain/kotlin/pw/binom/lua/KotlinLuaFunction.kt:24-42`): reads the *struct-wrapped pointer* at idx 1 via `Heap.getPtrFromPtr`, then `asStableRef<Any>().dispose()`. Same call shape on the Lua side but operates on a `klua_pointer` struct, not a raw mem address.
  - Callers that build userdata with `engine.userdataAutoGcFunction` as `__gc` get observably different behaviour depending on the target: JVM disposes by mem-addressed StaticRefs slot; POSIX disposes by `asStableRef`. If a common helper built via `expect` ever wires this up (today it does not), the contract would silently differ.
- **Fix sketch:**
  Either (a) drop `userdataAutoGcFunction` from the `expect` API if a single `userdataGc` is the intended behaviour, or (b) provide two distinct cfunctions on POSIX — one tailored to the mem-addressed userdata payload, one tailored to the closure-owned userdata payload — so the public surface mirrors JVM.

---

### 6. POSIX `LuaContextRegistry` is a single global slot and never unregisters on `close()` — closure-callback dispatch becomes silently broken between successive engines

- **Type:** Bug (cross-source-set lifecycle)
- **Severity:** Warning
- **Location:**
  - `src/posixMain/kotlin/pw/binom/lua/LuaContext.kt:38-55` — single-slot `LuaContextRegistry`, comment acknowledges the assumption.
  - `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:17` — `close()` is empty, doesn't touch the registry.
  - `src/posixMain/kotlin/pw/binom/lua/KotlinLuaFunction.kt:67-71` — `CLOSURE_FUNCTION` looks up `state → LuaContext` from this registry.
  - `src/posixMain/kotlin/pw/binom/lua/KotlinLuaFunction.kt:24-42` — `userdataGc` does the same lookup.
- **Description:**
  Trace the failure path for a user who instantiates two engines:
  1. `val a = LuaEngine()` → `LuaContext()` registers `(stateA, ctxA)`.
  2. `val b = LuaEngine()` → overwrites `current` with `(stateB, ctxB)`. The registry now points to `b` only; `a`'s entry is gone.
  3. Caller invokes a closure installed on `a` (e.g. `a.eval("f()")` where `f` is a CLOSURE_FUNCTION cclosure created on `a`). Lua calls `CLOSURE_FUNCTION` passing `stateA`. The C function calls `LuaContextRegistry.lookup(stateA)` → no entry → `return@staticCFunction 0` (returns 0 results; the dispatch is silently skipped).
  4. Same for `userdataGc` on the first engine's userdata — `__gc` becomes a no-op; the wrapped StableRef leaks for the process lifetime.
  The accompanying JVM side has an entirely different model: the JNI trampolines (`klua_callback_trampoline`, `klua_gc_trampoline`, `klua_userdata_gc_trampoline` — `src/jvmMain/c/klua_jni.c:120-226`) get the raw `lua_State*` from Lua's stack and pass it as `jlong` to Kotlin; no global registry needed. So multi-engine on JVM is fine.
  Note also: even with a single engine, `engine.close()` on POSIX does not clear `current` (`LuaContext.kt:38-55`), so any reference that triggers `CLOSURE_FUNCTION` after a POSIX `close()` will fall through to `return 0` from the cleared registry. JVM's `close()` nulls `statePtr` (`LuaContext.kt:114`) so Cleaner actions no-op; POSIX doesn't.
- **Fix sketch:**
  Switch `LuaContextRegistry` to a `CPointer<lua_State> → LuaContext` map (the existing comment suggests this), and have `LuaContext` register/unregister symmetrically in `init` + a real `close()`. Pair with the close() fix from finding 2.

---

### 7. Locking discipline inconsistent: POSIX `ObjectContainer` and `createUserData` paths use unsynchronized `HashMap`; JVM uses `synchronized(map)` / `AtomicInteger`

- **Type:** Concurrency / Design
- **Severity:** Warning
- **Location:**
  - `src/jvmMain/kotlin/pw/binom/lua/StaticRefs.kt:13-44` — `synchronized(map)` on every accessor; `AtomicInteger counter`.
  - `src/jvmMain/kotlin/pw/binom/lua/ObjectContainer.kt:108-130` — `synchronized` wrapping the inner `ClosureMap`'s get/set/remove/clear.
  - `src/posixMain/kotlin/pw/binom/lua/ObjectContainer.kt:9-72` — bare `HashMap` (`ptrToObj`, `objToPtr`), no synchronization.
  - `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:107-114, 117-123` — `createUserData` / `createAC` paths mutate a raw HashMap indirectly; `makeRef` calls `luaL_ref` which itself locks internally (single-threaded Lua), but on K/N multi-threaded mutation is the caller's responsibility.
- **Description:**
  Both targets declare the same `expect class ObjectContainer` and `LuaValue.Data` etc. The common code gives no thread-safety contract. JVM implementations assume every accessor can be called concurrently (cleaners run on `ReferenceHandler` thread, application code on user threads). POSIX implementations use plain `HashMap`. This is not strictly a bug — Kotlin/Native 5+ freezes the immutable state, but `ObjectContainer` exposes `add/remove/get/clear/...` which users will, on JVM, expect to be thread-safe given the cleaner pattern. Either:
  - commit to a thread-safe contract in common and synchronize POSIX too, or
  - document that POSIX's `ObjectContainer` (and any plugin code mutating the data) must be single-threaded.
- **Fix sketch:**
  Wrap the two maps in POSIX `ObjectContainer` with `kotlin.concurrent` synchronization (or document the single-thread restriction on the class) — make the property symmetric across source sets.

---

### 8. POSIX `UserData.dispose()` / `LightUserData.dispose()` are not idempotent; JVM versions are

- **Type:** Bug
- **Severity:** Warning
- **Location:**
  - `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:99-105, 121-129` — JVM `dispose()` short-circuits on null `ptr`, then `StaticRefs.dispose(p)` (safe on missing key) + `LuaNative.unref(state, LUA_REGISTRYINDEX, refId)` (safe on missing id) → idempotent.
  - `src/posixMain/kotlin/pw/binom/lua/LuaValue.kt:57-67, 96-100` — POSIX `dispose()` calls `ptr.asStableRef<Any>().dispose()`. Once disposed, calling `dispose()` again throws "illegal state: stable ref is already disposed" (kotlinx.cinterop contract).
- **Description:**
  Lua's `__gc` metamethod (`userdataGc`, `KotlinLuaFunction.kt:24-42`) invokes `asStableRef<Any>().dispose()` on the payload. If a user holds a Kotlin `LuaValue.UserData` reference and later calls `ud.dispose()` explicitly while the Lua userdata is still alive, the Lua GC then sees a same-keyed userdata later and tries to dispose the same StableRef again — second call crashes on POSIX, no-op on JVM. Plus `LightUserData` exposes the same divergence: `posix.dispose()` throws on a second call, JVM doesn't.
  Without an idempotency guard, POSIX `dispose()` cannot be safely called after the equivalent Lua-side cleanup.
- **Fix sketch:**
  POSIX `dispose()` should null the local pointer (`lightPtr = null` for `LightUserData`, `Heap.setPtrFromPtr(lk, null)` for `UserData`) before calling `asStableRef<Any>().dispose()`; the second call then sees `null` and short-circuits. This matches JVM `UserData.dispose()`'s `val p = ptr ?: return` and `LightUserData.dispose()`'s `val ptr = ptr ?: return`.

---

### 9. `LuaValue.Data` interface declared in common vs POSIX adds a `dispose()` member; creates a parity-blind surface

- **Type:** Design
- **Severity:** WeakWarning
- **Location:**
  - `src/commonMain/kotlin/pw/binom/lua/LuaValue.kt:14-17` — `expect interface Data : LuaValue { val value: Any? }` (no `dispose`).
  - `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:8-10` — `actual interface Data : LuaValue { actual val value: Any? }`.
  - `src/posixMain/kotlin/pw/binom/lua/LuaValue.kt:13-15` — `actual interface Data : LuaValue { actual val value: Any?; fun dispose() }`. Kotlin compiles this as an *additional* non-abstract member on the actual interface.
- **Description:**
  The public contract surfaced from `commonMain` does not mention `dispose()`. On JVM callers cannot call it (the JVM `actual interface Data` doesn't have it). On POSIX callers can. Code written in `commonMain` that wants to dispose a `LuaValue.Data` cannot name the method, so it must `as?` to a target-specific subtype or skip cleanup. This makes "Data cleanup" inconsistent by source set.
- **Fix sketch:**
  Promote `dispose()` to the `expect interface Data` in `commonMain` (with a JVM no-op default or JVM implementations that override it). Either that, or remove it from POSIX.

---

### 10. `LuaNative.reserveCallbackId(state)` Kotlin external declaration mismatched with JNI symbol `Java_pw_binom_lua_LuaNative_registerCallback` — declaration is dead

- **Type:** Dead code / Build risk
- **Severity:** Warning
- **Location:**
  - `src/jvmMain/kotlin/pw/binom/lua/LuaNative.kt:40` — `external fun reserveCallbackId(state: Long): Int`
  - `src/jvmMain/c/klua_jni.c:413-418` — exports `Java_pw_binom_lua_LuaNative_registerCallback`, *not* `Java_pw_binom_lua_LuaNative_reserveCallbackId`.
  - Internal usage of `reserveCallbackId` in Kotlin: none (grep confirms zero call sites).
- **Description:**
  The Kotlin declaration would resolve to a JNI symbol of the same name (`Java_pw_binom_lua_LuaNative_reserveCallbackId`); that symbol does not exist in the C source. The function is never called by Kotlin today, so the missing symbol is dormant. But the declaration is misleading: the *parallel* allocator on the C side (`alloc_cclosure_id` at `klua_jni.c:222-232`) lives in a Lua-registry table keyed by `"next_id"`, so its next-id is per-`lua_State`. Meanwhile the Kotlin `nextCallbackId()` (`LuaNative.kt:13, 99`) is JVM-global. These are two independent id spaces, neither is named consistently across JNI ↔ Kotlin.
  Failure mode if anyone adds a call to `reserveCallbackId`: `UnsatisfiedLinkError` at first execution in the JVM. The mismatch would only be caught in a runtime test that exercises the new path.
- **Fix sketch:**
  Either:
  1. Rename Kotlin's `reserveCallbackId` to `registerCallback` (matching the C side), or delete it entirely since `nextCallbackId` is what the codebase actually uses (`src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:17, 145`).
  2. If the intent is to use the C-side allocator, route `pushCClosure`/`setCallback` through it instead of duplicating ids.

---

### 11. Dead `UserDataAction` Cleaner class in JVM `LuaValue.kt`

- **Type:** Dead code
- **Severity:** WeakWarning
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:70-99` (declaration); `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:31-33` (the `UserData` class registers a `RefAction`, not a `UserDataAction`).
- **Description:**
  JVM `UserData` is registered with `REFCLEANER.register(this, RefAction(ll, refId))`. `UserDataAction` is fully implemented but never instantiated. The comment at line 70 describes the behavior the class would provide, but that behavior is actually delivered by the chain "Cleaner (UserData) → RefAction → unref registry entry → Lua GC → __gc → userdata_gc_trampoline → disposeUserdata(mem) → StaticRefs.dispose(mem)".
  Having both classes in the file invites a future maintainer to wire up `UserDataAction` to `UserData` and break that chain (the comment in `JvmAcDisposeTest.kt:329-331` suggests this very regression has been on the team's mind).
- **Fix sketch:**
  Delete `UserDataAction` (lines 70-99), or repurpose it as the registered action (replacing the `RefAction` registration) and update the chain accordingly — but only if a meaningful behavior change is intended.

---

### 12. `makeRef` on POSIX uses `!!` on `lua_topointer` and adds a `try/catch` that only `printStackTrace`

- **Type:** Robustness / Debug noise
- **Severity:** WeakWarning
- **Location:**
  - `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:84-101` — both `makeRef(FunctionValue)` and `makeRef(TableValue)` use `try { ... } catch (e: Throwable) { e.printStackTrace(); throw e }`.
  - JVM equivalent (`LuaEngine.kt:95-114`) has no such swallow.
- **Description:**
  The `printStackTrace()` is debug noise in a release library and adds nothing — the exception is re-thrown unchanged, so the print is just a duplicate of what the caller will get. Also `lua_topointer(...)!!` will NPE on values that do not have a pointer (e.g., primitives, NaN-boxed values) — the JVM equivalent calls `toPointer` which silently returns 0.
- **Fix sketch:**
  Drop the `try/catch` entirely. Replace `!!` with a null-check that throws a meaningful exception (`LuaException("Cannot make ref of value with no pointer (type=$t) at $stack")`).

---

### 13. JVM `makeUserdataGcRef` is unused but matches the documented contract; `makeAutoGcRef` is also unused internally

- **Type:** Dead code (informational)
- **Severity:** Info
- **Location:**
  - `src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:10-25` — both defined.
  - Grep confirms no caller in either `jvmMain` or `posixMain` for either of these private helpers beyond their declarations.
- **Description:**
  These helpers exist to satisfy the *expect* contract `val closureAutoGcFunction` and `val userdataAutoGcFunction`. That's the legitimate reason — the values are exposed as `actual val`. But the `userdataAutoGcFunction` on POSIX is a duplicate of `closureAutoGcFunction` (see Finding 5), so the second pass through `makeUserdataGcRef` on POSIX is wasted work (and would crash if it were ever invoked, since POSIX's userdata-gc path uses the same cfunction closure).
- **Fix sketch:**
  Coordinate with Finding 5 — collapse or remove the duplicate.

---

### 14. `pushCClosure` in JNI ignores the `n` upvalue count; upvalues are hardcoded to 1

- **Type:** API drift between Kotlin and C
- **Severity:** Info
- **Location:**
  - `src/jvmMain/kotlin/pw/binom/lua/LuaNative.kt:31` — `external fun pushCClosure(state: Long, callbackId: Int, n: Int)`
  - `src/jvmMain/c/klua_jni.c:376-384` — implementation pushes a single integer as upvalue #1, ignores `n`.
  - POSIX: `src/posixMain/kotlin/pw/binom/lua/LuaValueWriter.kt:7-12` uses `lua_pushcclosure(state, value.ptr, value.upValues.size)` directly through cinterop, supporting arbitrary upvalue counts.
- **Description:**
  The JNI binds a parameter the implementation never honors. A future attempt to push a cclosure with multiple upvalues (e.g., `__call` cclosure carrying both a function ref and a userdata-pointer payload) would silently truncate. Today nothing in the Kotlin layer asks the JNI for `n > 1`, so the dead parameter is dormant.
- **Fix sketch:**
  Either (a) remove the `n` parameter from the Kotlin declaration and the JNI (drop the C-side `(void)n;`), or (b) actually push `n` upvalues in C if a target shape ever requires it.

---

## FINDINGS COUNT
14

## SUMMARY
- 1 hard correctness bug: JVM `pcallCall` (`LuaValue.kt:440-457`) mis-maps Lua 5.4 error codes (`4/5/6` instead of `2/4/5`) so runtime errors from `FunctionRef.call`/`TableRef.call`/`UserData.call` throw `RuntimeException("Unknown pcall status: 2")` instead of `LuaException(msg)`. This is the most consequential cross-file inconsistency; JVM `LuaEngine.pcallProcessing` and POSIX `pcallProcessing` both have the right codes.
- 2 contract / lifecycle failures: POSIX `LuaEngine.close()` is empty; POSIX `createUserData(LightUserData)` installs no `__gc` and does not transfer ownership.
- 1 stack leak: POSIX `call(functionName, ...)` nil branch does not pop.
- 2 partial / dead public surfaces: `userdataAutoGcFunction` is dead-on-arrival on POSIX; `LuaNative.reserveCallbackId` is a name-mismatched JNI declaration never called from Kotlin.
- 1 multi-engine hazard: POSIX `LuaContextRegistry` is a single-slot map and not cleared by `close()`, so a second engine silently orphans the first's closures.
- 4 design / parity / robustness issues: locking discipline diff, `dispose()` idempotency diff, `Data.dispose()` extra on POSIX, dead `UserDataAction` Cleaner class, `printStackTrace` debug noise, `lua_topointer(...)!!` non-null assert.
- 1 informational note: `pushCClosure`'s `n` upvalue parameter is ignored by JNI.
