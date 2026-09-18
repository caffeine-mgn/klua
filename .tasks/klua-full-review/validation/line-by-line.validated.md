# Validation: Line-by-line (L1-L23)

Spot-verified critical claims via direct read of cited files. Cross-confirmed findings with other reviewers.

## L1 — posix userdataGc double-derefs the StableRef pointer
- verdict: TP
- severity: Error
- evidence: src/posixMain/kotlin/pw/binom/lua/KotlinLuaFunction.kt:33-34 — `val ptr = Heap.getPtrFromPtr(state.readUserData(1))`. Direct read confirmed.
- trace: If `state.readUserData(1)` already dereferences (typical for a Lua-side "read userdata payload" extension), wrapping the result in another `Heap.getPtrFromPtr` reads the 8 bytes at that pointer as if they were a `klua_pointer`. The subsequent `.asStableRef<Any>().dispose()` then frees an arbitrary address.
- Not cross-confirmed by other reviewers for THIS specific double-deref (other reviewers found related but distinct GC bugs in same file).

## L2 — JVM setAC overwrites __gc with closure-gc trampoline
- verdict: TP
- severity: Error
- evidence: src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:185-192 — setAC overwrites `__gc` with `closureAutoGcFunction` (which calls `disposeCallback(id)` on a freshly-allocated, never-registered id).
- trace: Cross-confirmed by Removed-behavior #1. Real StaticRefs leak.

## L3 — posix LuaEngine.close() is a no-op
- verdict: TP
- severity: Error
- evidence: src/posixMain/.../LuaEngine.kt:20-21 — `actual override fun close() { }`. Direct read confirmed.
- trace: Cross-confirmed 5+ times (C2/M#2/RB#3/Nat#4/M#3). The strong registry retention amplifies.

## L4 — posix wrapper Cleaners UAF if engine GC'd first
- verdict: TP
- severity: Error
- evidence: src/posixMain/.../Lua.kt:14-17 — `createCleaner1(state, ref)` action unconditionally calls `disposeRef`.
- trace: JVM equivalent (LuaValue.kt:335-345 RefAction.run) reads `ll.state` (which gets nulled by engine.close()) and skips on 0L; POSIX has no null sentinel on `state`.

## L5 — JVM pcallCall uses status 4/5/6 instead of 2/4/5
- verdict: TP
- severity: Error
- evidence: src/jvmMain/.../LuaValue.kt:432-459 — direct read at line 447-456. Cases `4 -> LuaException`, `5 -> "memory allocation error"`, `6 -> "message handler error"`, `else -> "Unknown pcall status: $r"`. Lua 5.4 statuses are LUA_OK=0, LUA_ERRRUN=2, LUA_ERRMEM=4, LUA_ERRERR=5 — so LUA_ERRRUN falls into else.
- trace: Cross-confirmed 4 times (R5/C1/Sec#3/L8). Direct read confirms the exact branches.

## L6 — posix ObjectContainer.removeClosure always returns false / leaves stale pointer
- verdict: TP
- severity: Error
- evidence: src/posixMain/.../ObjectContainer.kt:58-66 — `removeClosure(FunctionRef)` checks ptr against `CLOSURE_FUNCTION` then calls `remove(data.toValue())`; `remove(data: Any)` looks up in `objToPtr` by the `FunctionValue` instance (not the original `LuaFunction`), miss → false.
- trace: Confirmed via the `ObjectContainer.add` map being keyed by LuaFunction not FunctionValue. Plus the stale-pointer half: stale entry is left in objToPtr.

## L7 — posix LuaContextRegistry single global slot
- verdict: TP
- severity: Error
- evidence: src/posixMain/.../LuaContext.kt:26-56 — `private var current: Pair<LuaState, LuaContext>?`. Direct read confirmed.
- trace: Cross-confirmed 6+ times (F11/C6/Sec#6/RB#4/Nat#5/M#6/L7).

## L8 — JVM eval mislabels Lua status codes
- verdict: TP
- severity: Error
- evidence: src/jvmMain/.../LuaEngine.kt:51-65. JVM `eval` `when (r)` paths vs LUA_ERRSYNTAX(3)/LUA_ERRMEM(4)/LUA_ERRERR(5). POSIX counterpart (src/posixMain/.../LuaEngine.kt:39-50, direct read) handles statuses correctly.
- trace: Stack leak per error path; status 3 (ERRSYNTAX) and 5 (ERRERR) mishandled.

## L9 — Lua integers read back as Number, never LuaInt
- verdict: TP
- severity: Error
- evidence: src/posixMain/.../LuaValueReader.kt:36-42 — `LUA_TNUMBER -> LuaValue.Number(lua_tonumberx(state, index, null))`. JVM src/jvmMain/.../LuaContext.kt:55-56 — `3 -> LuaValue.Number(LuaNative.toNumber(statePtr, index))`. `lua_isinteger`/`LuaNative.isInteger` never called.
- trace: Confirmed direct. `LuaValue.LuaInt` is unreachable from any read on either platform.

## L10 — posix call(functionName) leaks stack on nil-branch
- verdict: TP
- severity: Warning
- evidence: src/posixMain/.../LuaEngine.kt:52-70 — `if (lua_isnil1(ll.state, -1)) throw ...` — no `lua_pop`. Cross-cited as M#3 / C4.
- trace: Direct read confirmed.

## L11 — posix readValue LUA_TFUNCTION ref=false produces FunctionValue(ptr=null)
- verdict: TP
- severity: Warning
- evidence: src/posixMain/.../LuaValueReader.kt:40-58 — branch unconditionally stores `lua_tocfunction` result which can be null for Lua-defined functions.
- trace: Future LuaEngine.set/pushValue calling `lua_pushcclosure(state, null, n)` is undefined.

## L12 — JVM readValueAt (table, ref=false) recursion not wrapped in checkState
- verdict: TP
- severity: Warning
- evidence: src/jvmMain/.../LuaContext.kt:46-109. POSIX uses `ll.state.checkState { ... }` (src/posixMain/.../lua_funcs.kt).
- trace: No JVM equivalent; the comment referring to commit 6dc634d says exactly this was the silent-stack-imbalance source.

## L13 — JVM getMetatable / TableRef.toValue allocate transient registry slots
- verdict: TP
- severity: Warning
- evidence: src/jvmMain/.../LuaValue.kt:269-283, 389-401 — both call `ll.readValue(-1, true)` for tables → creates TableRef backed by `luaL_ref` slot.
- trace: Each userdata.metatable / TableRef.toValue grows the Lua registry until the wrapper is GC'd.

## L14 — JVM createUserData(LightUserData) silently nulls on reuse
- verdict: TP
- severity: Warning
- evidence: src/jvmMain/.../LuaEngine.kt:106-128 — `StaticRefs.store(mem, underlying)` then `StaticRefs.dispose(value.ptr)`. Direct read confirmed.
- trace: Subsequent reuse sees `StaticRefs.get(disposed_ptr) == null`.

## L15 — posix ObjectContainer.add dedups by equals
- verdict: TP
- severity: Warning
- evidence: src/posixMain/.../ObjectContainer.kt:14-24 — `objToPtr[data]` uses default HashMap equality.
- trace: Two value-equal objects share one StableRef; remove disposes for both.

## L16 — JVM StaticRefs.intern does not dedupe
- verdict: TP
- severity: WeakWarning
- evidence: src/jvmMain/.../StaticRefs.kt:9-40 — `fun intern(value: Any?): Long` allocates fresh counter key.
- trace: Repeated add/remove cycles leak StaticRefs entries between calls.

## L17 — JVM pcallCall reads indices 1..count assuming stack below is empty
- verdict: TP
- severity: Warning
- evidence: src/jvmMain/.../LuaValue.kt:432-459 — `(1..count).map { ll.readValue(it, true) }`. Latent bug; all current callers satisfy topBefore==1.
- trace: Correct fix: read from `topBefore`.

## L18 — posix LuaInt.equals compares boxed Long instead of is LuaInt
- verdict: TP
- severity: WeakWarning
- evidence: src/posixMain/.../LuaValue.kt:94-96 — `value == other` with `value: Long`. Direct read confirmed.
- trace: Cross-confirmed by Multi #1.

## L19 — JVM LuaContext.wrap exposes a close() that would tear down the owning engine
- verdict: TP
- severity: Warning
- evidence: src/jvmMain/.../LuaContext.kt:15-17, 111-119 — private secondary ctor + companion `wrap()`. Comment says "must not escape the callback scope".
- trace: Cross-confirmed by Nat #1 (which adds the deeper UAF analysis: wrapper's statePtr is never nulled by engine.close()).

## L20 — NativeLoader TOCTOU race
- verdict: TP
- severity: WeakWarning (severe Security aspect covered by Sec#1 — there Error)
- evidence: src/jvmMain/.../NativeLoader.kt:12-23 — `if (!cached.exists()) { extractFromJar(...) }` without sync.
- trace: Cross-cited Sec#1 (Error, the bigger CVE-class issue), Nat #17.

## L21 — Dead classes LuaCtx / LuaStateAndLib
- verdict: TP
- severity: WeakWarning
- evidence: src/posixMain/.../LuaCtx.kt:9-15 + LuaStateAndLib.kt:6. Multi-greps confirm zero usage.
- trace: Cross-cited R2.

## L22 — JVM ObjectContainer.remove(data) only removes one entry by identity
- verdict: TP
- severity: WeakWarning
- evidence: src/jvmMain/.../ObjectContainer.kt:49 + StaticRefs.removeIfMatches at src/jvmMain/.../StaticRefs.kt:36-46. Direct read.
- trace: Cross-cited Multi #7 partial.

## L23 — LightUserData(ptr = 0L) on JVM skips Cleaner
- verdict: TP
- severity: Info
- evidence: src/jvmMain/.../LuaValue.kt:81-90 — `ptr?.let { ... }` short-circuit when ptr is null.
- trace: Defensive note only; current code cannot reach this branch.

## VERDICT COUNTS
- TP: 23
- FP: 0
- NeedMoreData: 0
- By severity (TP only): Error 9, Warning 8, WeakWarning 5, Info 1

## TOP-RISK TP
- L5 (Error): `pcallCall` mis-maps status codes — 4+ reviewer consensus; direct-read verified LuaValue.kt:432-459.
- L1 (Error): POSIX `userdataGc` double-deref of StableRef — direct-read verified KotlinLuaFunction.kt:33-34.
- L7 (Error): POSIX `LuaContextRegistry` single-slot — direct-read + 6+ reviewer consensus.
- L3 (Error): POSIX `LuaEngine.close()` no-op — direct-read + 5+ reviewer consensus.
- L9 (Error): `LuaValue.LuaInt` unreachable from any read — direct-read on both targets.
- L4 (Error): POSIX wrapper Cleaner UAF — see also Nat #1.
- L6 (Error): POSIX `removeClosure` always-false + stale pointer.
- L8 (Error): JVM `eval` mislabels Lua statuses + stack leak.
- L2 (Error): JVM `setAC` installs wrong `__gc` trampoline.
