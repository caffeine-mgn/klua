# Removed-Behavior Review — klua

Focus: absent guards, lost error handling, leaked responsibilities, missing cleanup paths, expect/actual no-ops, stub bodies, swallowed exceptions.

## Issue 1
- **Title:** JVM `setAC` installs the wrong `__gc` handler, leaking the `StaticRefs` entry it claims to release
- **Type:** Bug
- **Severity:** Error
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:170-177` — `actual fun setAC(userdata: LuaValue.UserData) { ... table["__gc".lua] = closureAutoGcFunction ... }`
- **Description:** `setAC` overwrites the userdata's `__gc` with `closureAutoGcFunction`, which is `makeAutoGcRef()` (the `klua_gc_trampoline` whose upvalue is a fresh, never-registered callback id). On `__gc` that trampoline calls `LuaNative.disposeCallback(id)` — a no-op for an id that was never added to `callbacks`. The KDoc explicitly promises "dispose stable reference to pointer in [userdata]", so the `StaticRefs` entry created by `createUserData(LightUserData)` at `LuaEngine.kt:148` (`StaticRefs.store(mem, underlying)`) is stranded for the lifetime of the JVM. `createAC(LightUserData)` (LuaEngine.kt:194-198) calls `setAC` and therefore leaks one `StaticRefs` slot per invocation; the rest of the AC paths (`createAC(Any?)` via `createUserData`) are unaffected because they bypass `setAC`. Posix is correct because both fields reference the same `userdataGc` cfunction — the discrepancy is JVM-specific.
- **Fix sketch:** In `setAC`, install `userdataAutoGcFunction` (or `LuaNative.pushUserdataGcFunction` on a fresh cclosure) instead of `closureAutoGcFunction`, so `disposeUserdata(mem)` fires on Lua `__gc` and drops the `StaticRefs` entry.

## Issue 2
- **Title:** JVM `ObjectContainer.removeClosure(FunctionRef)` is a hard-coded no-op stub
- **Type:** Bug
- **Severity:** Error
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/ObjectContainer.kt:58` — `actual fun removeClosure(data: LuaValue.FunctionRef): Boolean = false`
- **Description:** The commonMain contract (`ObjectContainer.kt:18`) requires this overload; posix implements it (`posixMain/.../ObjectContainer.kt:39-43`). The JVM actual unconditionally returns `false` and does nothing — the bridge id stays in `LuaNative.callbacks`, the registry entry for the underlying function value stays on the Lua stack, and the closure's `StaticRefs`/Lua references are never released. Callers that remove a function ref via this API (e.g. test scaffolding, future SDK consumers) silently leak. Returning `false` also misleads callers into believing the entry was absent, when in fact the removal was never attempted.
- **Fix sketch:** Look up the matching bridge id for the `FunctionRef` (resolve via its `refId`/`ptr` like `getClosure` does for `FunctionValue`), then `LuaNative.unregisterCallback(id)` and remove from `closures`.

## Issue 3
- **Title:** Posix `LuaEngine.close()` body is empty; the state is never freed for the last live engine
- **Type:** Bug
- **Severity:** Error
- **Location:** `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:21-23` — `actual override fun close() { }`
- **Description:** `LuaEngine` implements `AutoCloseable` (commonMain declaration `LuaEngine.kt:10`); posix delegates cleanup to a `createCleaner` on `LuaContext` (`LuaContext.kt:14-17`) but `close()` itself does nothing. Worse, `LuaContextRegistry.current` (`LuaContext.kt:32`) strongly references the `LuaContext`, so the Cleaner can never fire for the active engine: the registry keeps `ctx` reachable, the Cleaner is registered on `ctx`, so `ctx` never becomes phantom-reachable, so `lua_close` never runs. The only path that closes a state is overwriting the registry slot with a *second* `LuaContext`. Any program using `engine.use { }` or explicitly calling `close()` leaves the Lua state (and its loaded libs/registry) alive until process shutdown or a second engine is constructed.
- **Fix sketch:** In `close()`, call `LuaContextRegistry.unregister(ll.state); lua_close(ll.state)`, and stop strongly retaining `ctx` from the registry (use a weak reference or clear the slot on close).

## Issue 4
- **Title:** `LuaContextRegistry` strongly retains the active `LuaContext`, suppressing its Cleaner
- **Type:** Bug
- **Severity:** Error
- **Location:** `src/posixMain/kotlin/pw/binom/lua/LuaContext.kt:31-49` — `private var current: Pair<LuaState, LuaContext>? = null`
- **Description:** `register` stores `(state, ctx)` strongly; `unregister` is only called from the Cleaner that is registered on `ctx`. This is a self-suppressing lifecycle: the Cleaner fires only when `ctx` is phantom-reachable, but `current.second` keeps `ctx` strongly reachable. The single active engine therefore never has its Cleaner run. The accompanying KDoc ("Single-slot is fine… If that ever changes, switch to a CMap") is misleading — it isn't the multi-engine concurrency that is broken, it is the basic liveness of the active engine's state.
- **Fix sketch:** Store a `WeakReference<LuaContext>` keyed by `state`, or evict `current` from `close()` so the Cleaner can fire; alternatively register the Cleaner before `register()` and have `register()` only stash a weak handle.

## Issue 5
- **Title:** JVM `ObjectContainer.clear()` leaves bridge entries in `LuaNative.callbacks`
- **Type:** Bug
- **Severity:** Warning
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/ObjectContainer.kt:54-56` — `actual fun clear() { closures.clear() }`
- **Description:** `clear()` empties the `ClosureMap` but never iterates the removed entries to call `LuaNative.unregisterCallback(id)`. The per-bridge Cleaner (`BridgeCleaner` at lines 73-83) only fires after the container becomes phantom-reachable, which can be much later — and the KDoc on `clear()` (`commonMain/.../ObjectContainer.kt:5`) explicitly says "Call for prevent memory leak". Callers who call `clear()` to release resources still leak one entry per prior `makeClosure` until the container is collected.
- **Fix sketch:** Track the bridge ids (e.g. `synchronized(closures) { it.keys.toList() }`) and `LuaNative.unregisterCallback(id)` for each before clearing.

## Issue 6
- **Title:** Posix `ObjectContainer.removeClosure(FunctionValue)` does not purge the container's pointer maps
- **Type:** Bug
- **Severity:** Warning
- **Location:** `src/posixMain/kotlin/pw/binom/lua/ObjectContainer.kt:45-53` — `if (... func.value is LuaFunction) { func.dispose(); return true }`
- **Description:** The function only disposes the `LightUserData`'s `StableRef` and returns `true`. It does not remove the corresponding entries from `ptrToObj`/`objToPtr`. The next `add(sameData)` will find the stale `objToPtr[data]` and return a `LightUserData` whose `lightPtr` points at the now-disposed `StableRef`; subsequent `.value` lookups walk a freed `StableRef`. The maps also grow unbounded across repeated `removeClosure` cycles, contradicting the comment "Make sure you called ObjectContainer.clear for cleanup resource".
- **Fix sketch:** Also do `objToPtr.remove(func.value)` and `ptrToObj.remove(func.lightPtr)` (guarding against null) before returning.

## Issue 7
- **Title:** Posix `createACClosure` stores the same `StableRef` in two places; `__gc` leaves the closure's upvalue dangling
- **Type:** Bug
- **Severity:** Error
- **Location:** `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:130-153` — `val ref = StableRef.create(func); ... userData.metatable = TableValue("__call".lua to luaFunc, "__gc".lua to closureAutoGcFunction)`
- **Description:** The `StableRef` is held both as the `__call` closure's only upvalue (a `LightUserData`) and as the userdata payload read by `userdataGc`. `userdataGc` disposes the `StableRef` once on Lua `__gc`, but the `__call` closure (a Lua closure, not GC'd in the same sweep) still references the same `StableRef` via its upvalue. Any subsequent invocation of the closure (e.g. via a long-lived `FunctionRef` or via metatable re-use) calls `funcPtr.asStableRef<LuaFunction>().get()` on a freed `StableRef` — use-after-free. The same dual-storage pattern is the source of the JVM `createACClosure` `__gc` callback-id mismatch already fixed in commit `d90a8f8`; the posix side has the analogous structural bug.
- **Fix sketch:** Make the userdata payload the single source of truth — add a plain cfunction (no upvalues) for `__call` that reads the `StableRef` from `lua_touserdata(L, 1)` and dispatches, leaving the closure-based `__call` path with no upvalue to dangle.

## Issue 8
- **Title:** Posix `createAC(Any?)` exception path leaks the freshly created `StableRef`
- **Type:** Bug
- **Severity:** Warning
- **Location:** `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:172-180` — `try { val ref = value?.let { StableRef.create(it) }?.asCPointer(); return createAC(LightUserData(ref)) } catch (e: Throwable) { e.printStackTrace(); throw e }`
- **Description:** The catch only prints the stack trace and rethrows — it does not `ref.dispose()`. Compare to `createUserData(Any)` (`LuaEngine.kt:117-128`), which correctly disposes `ptr` in its catch. Under any throw from `createAC(LightUserData)` (Lua OOM, etc.) the `StableRef` and the pinned Kotlin object leak permanently; `printStackTrace` also writes to stderr in production.
- **Fix sketch:** Capture the `StableRef` into a local, `try { ... } catch (t: Throwable) { ref?.dispose(); throw t }`.

## Issue 9
- **Title:** Dead class `LuaCtx` duplicates `LuaContext` and is never referenced
- **Type:** Maintainability
- **Severity:** WeakWarning
- **Location:** `src/posixMain/kotlin/pw/binom/lua/LuaCtx.kt:1-15` — `class LuaCtx private constructor(val state: LuaState)`
- **Description:** A full `LuaCtx` implementation with its own `createCleaner` exists alongside the actual `LuaContext` (`posixMain/.../LuaContext.kt`). No code outside this file references `LuaCtx` (verified via `search_for_text "LuaCtx("`). It carries its own `createCleaner` which would `lua_close` an unrelated state if accidentally instantiated. Dead code that adds maintenance surface and reader confusion.
- **Fix sketch:** Delete `src/posixMain/kotlin/pw/binom/lua/LuaCtx.kt`.

## Issue 10
- **Title:** `KotlinLuaFunctions.kt` is an empty file (only `@file:OptIn` + imports)
- **Type:** Maintainability
- **Severity:** WeakWarning
- **Location:** `src/posixMain/kotlin/pw/binom/lua/KotlinLuaFunctions.kt:1-7` — `@file:OptIn(ExperimentalForeignApi::class, ExperimentalForeignApi::class, ExperimentalForeignApi::class) ... import kotlinx.cinterop.ExperimentalForeignApi import platform.internal_lua.*`
- **Description:** File has zero top-level or function declarations — only an opt-in annotation and unused imports. Looks like a leftover stub from a refactor. Provides no value and signals incompleteness to readers.
- **Fix sketch:** Delete the file.

## Issue 11
- **Title:** Dead interfaces `InputVarargs` / `OutputVarargs` have no implementers
- **Type:** Maintainability
- **Severity:** WeakWarning
- **Location:** `src/commonMain/kotlin/pw/binom/lua/InputVarargs.kt:1-6` and `src/commonMain/kotlin/pw/binom/lua/OutputVarargs.kt:1-5`
- **Description:** Both interfaces exist in commonMain but no `actual`/`expect` class implements them across jvmMain/posixMain. They are not referenced anywhere in production code (only in their own declarations). Either they document a planned multi-return-value API or they are leftovers from an earlier design.
- **Fix sketch:** Either delete both files or implement the multi-return `call` overload that consumes them.

## Issue 12
- **Title:** JVM `LuaEngine.printStack` body is a stub that only prints "doesn't provide any Lua Stack"
- **Type:** Maintainability
- **Severity:** WeakWarning
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/DebugTool.kt:1-9` — `actual fun LuaEngine.printStack(message: String?) { ... println("JVM KLua implementation doesn't provide any Lua Stack") ... }`
- **Description:** CommonMain declares `printStack` as a debug tool to "Prints Lua stack as is". Posix prints real stack info (`posixMain/.../DebugTool.kt:21-37`). JVM prints a literal message saying it does not provide stack info — a clear placeholder/stub. Deprecated, but still exported as `internal expect`. If left in place it should actually walk the Lua stack via `LuaNative.*` (the JNI bindings already cover `getTop`/`type`/`toString`).
- **Fix sketch:** Either implement by looping `LuaNative.getTop` → `type` → `toString` and calling `StdOut.info`, or remove the JVM `actual` and suppress the `expect` via a no-op annotation.

## Issue 13
- **Title:** `StdOut.info` silently swallows output when no handler has been installed
- **Type:** Bug
- **Severity:** Warning
- **Location:** `src/commonMain/kotlin/pw/binom/lua/StdOut.kt:1-8` — `fun info(txt: String) { func?.invoke(txt) }`
- **Description:** `func` defaults to `null` and is `internal var` (mutable global). Production code that calls `StdOut.info(...)` while a debug consumer has not yet wired `StdOut.func` silently drops messages. With no default to `println` or stderr, callers cannot tell whether they are missing output or whether the code path was reached. The same global is also unsynchronised, so concurrent `info`/`func =` accesses can race (the `internal var` is not volatile or atomic).
- **Fix sketch:** Default `func` to a no-op-and-log sink, or use `println(txt)` as a fallback; mark `func` volatile/atomic for cross-thread visibility.

## Issue 14
- **Title:** Posix `LuaEngine.makeRef` catches `Throwable` only to `printStackTrace` and rethrow
- **Type:** BestPractice
- **Severity:** WeakWarning
- **Location:** `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:89-96` — `try { ... } catch (e: Throwable) { e.printStackTrace(); throw e }`
- **Description:** The try/catch adds nothing but stderr noise; if `ll.pushValue`/`lua_topointer`/`makeRef` throws, the caller will see the same exception one frame up with an extra stack frame printed. `printStackTrace` in production code is the textbook "swallowed/handled" anti-pattern. The same shape repeats at `createAC(Any?)` (`LuaEngine.kt:178`).
- **Fix sketch:** Remove the try/catch wrappers entirely.

## Issue 15
- **Title:** Posix `CLOSURE_FUNCTION` silently returns 0 when the engine is no longer registered
- **Type:** Bug
- **Severity:** Warning
- **Location:** `src/posixMain/kotlin/pw/binom/lua/KotlinLuaFunction.kt:55-57` — `val ctx = LuaContextRegistry.lookup(state) ?: return@staticCFunction 0`
- **Description:** When `lookup` returns null (e.g., engine was closed but a closure is still being invoked from Lua), the cfunction returns 0 with no return values and no error. The Lua caller sees zero values back, indistinguishable from a legitimate empty-result call. This masks real failures (closed-engine invocation, registry eviction races) and produces confusing downstream errors like "attempt to index a nil value" far from the root cause.
- **Fix sketch:** `luaL_error(state, "klua: LuaContext not registered for state")` so the failure surfaces at the call site; the cfunction is permitted to longjmp out of Lua frames.

## FINDINGS COUNT
15

## SUMMARY
The largest cluster of removed-behavior is in the JVM AC/closure plumbing: `setAC` installs the wrong `__gc` cfunction (Issue 1, Error) and `removeClosure(FunctionRef)` is a hard-coded `false` stub (Issue 2, Error); `ObjectContainer.clear()` also leaks `LuaNative.callbacks` entries (Issue 5). On posix, the `LuaContextRegistry` strong-references the active `LuaContext`, suppressing its Cleaner, so `LuaEngine.close()` — whose body is empty — never frees the Lua state (Issues 3-4, Error); `createACClosure` keeps the same `StableRef` in both the closure upvalue and the userdata payload, so Lua's `__gc` leaves the closure's upvalue pointing at a freed `StableRef` (Issue 7, Error); and `createAC(Any?)` discards its freshly made `StableRef` on exception (Issue 8). The remaining items are smaller cleanup, dead-code, and silent-swallow problems (`LuaCtx`, empty `KotlinLuaFunctions.kt`, dead `InputVarargs`/`OutputVarargs`, JVM `printStack` stub, `StdOut` swallowing output, noisy `printStackTrace` wrappers, `CLOSURE_FUNCTION` silent zero-return). Several of these are pre-existing shapes that the recent JVM-side fixes did not catch; together they account for most of the long-tail leak surface the recent GC stress tests have been chasing.
