# Validation: Efficiency (F1-F13)

Validated against `src/commonMain`, `src/jvmMain`, `src/posixMain`. Verdict per finding is from reading the cited locations.

## F1 — StaticRefs uses one global monitor
- verdict: TP
- severity: Warning
- evidence: src/jvmMain/kotlin/pw/binom/lua/StaticRefs.kt:13-44 — `private val map = HashMap<Long, Any?>()` + 5× `synchronized(map)`. `LuaNative.callbacks` (src/jvmMain/.../LuaNative.kt:99-114) already uses `ConcurrentHashMap` — same pattern would apply.
- trace: Every `intern`, `store`, `get`, `dispose`, `removeIfMatches`, `size` serializes on one monitor; `UserData.value`/`LightUserData.value` (src/jvmMain/.../LuaValue.kt:36-49, 117-129) call `StaticRefs.get` on every read. In multi-threaded use the bridge is serialized.
- Note: real bottleneck; matches Reuse #10/Removed cross-references.

## F2 — readValue always calls absIndex via JNI
- verdict: TP
- severity: Warning
- evidence: src/jvmMain/kotlin/pw/binom/lua/LuaContext.kt:41-43 — `val abs = LuaNative.absIndex(statePtr, index)`. `absIndex` is a JNI call regardless of sign.
- trace: pcallProcessing and pcallCall loop `(1..count).map { ll.readValue(it, true) }` — every iteration crosses JNI once for absIndex before the type and toX calls.
- Cheap fix: `val abs = if (index > 0) index else LuaNative.absIndex(statePtr, index)`.

## F3 — UserData.ptr recomputed on every access
- verdict: TP
- severity: Warning
- evidence: src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:36-42 — `val ptr: Long? get() { ll.push(this); val p = LuaNative.userdataPtr(ll.state, -1); LuaNative.pop(ll.state, 1); ... }` followed by `value` calling `StaticRefs.get(p)`.
- trace: 3 JNI crossings per `ptr` getter. Address is stable while registry ref holds the userdata, so cache once.

## F4 — POSIX checkState adds 2× lua_gettop on every TableRef op
- verdict: TP
- severity: Warning
- evidence: src/posixMain/kotlin/pw/binom/lua/lua_funcs.kt:23-31 — `internal inline fun <T> LuaState.checkState(...)`. Used by TableRef and FunctionRef ops in src/posixMain/.../LuaValue.kt:212-310.
- trace: Hot path on every `pairs` iteration pays 2 native calls + try/finally. Gate behind debug flag.

## F5 — pcallCall allocates args.toList()
- verdict: TP
- severity: WeakWarning
- evidence: src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:56,269,310 — `pcallCall(ll, args.toList())`. List + iterator alloc per call.
- trace: `pcallCall(ll, args)` with `Array<out LuaValue>` overload avoids it. Same at invokeCallback `results.forEach`.

## F6 — Cleaner pressure on Lua-side wrappers
- verdict: TP
- severity: Warning
- evidence: src/jvmMain/.../LuaValue.kt:22-25, 70-99, 331-337 — UserData/LightUserData/RefAction Cleaners registered per Lua-side value read.
- trace: Per wrapper: 1 obj + 1 Cleanable + 1 Runnable + 1 Cleaner-list node. Heavy pressure in tight Lua-side iteration. Possibly linked to recent 02de357 GC stress reports.

## F7 — Metatable/per-call HashMap allocations
- verdict: TP
- severity: WeakWarning
- evidence: src/jvmMain/.../LuaEngine.kt:158-192 — `createACClosure` and `setAC` build fresh `TableValue("__gc".lua to ..., "__call".lua to ...)` per closure.
- trace: Two HashMap+Pair+`.lua`-wrapper pairs per closure; can be a single shared metatable template.

## F8 — LuaNative.callbacks map doesn't shrink
- verdict: TP
- severity: Info
- evidence: src/jvmMain/.../LuaNative.kt:99-114 — ConcurrentHashMap with disposeCallback; no shrink.
- trace: Peak usage, not live count, drives memory. Real but Info-level — fix only if profiling shows it.

## F9 — POSIX ObjectContainer two HashMaps
- verdict: TP
- severity: WeakWarning
- evidence: src/posixMain/.../ObjectContainer.kt:7-75 — `ptrToObj`, `objToPtr`, `add`/`remove`/`clear`. 3 native allocations per add.
- trace: Single map + `WeakHashMap` for reverse lookup is cheaper.

## F10 — JVM ClosureMap synchronizes per-get
- verdict: TP
- severity: Info
- evidence: src/jvmMain/.../ObjectContainer.kt:97-114 — ClosureMap synchronized get/set.
- trace: Bridge callback hot path; could be ConcurrentHashMap like LuaNative.callbacks.

## F11 — POSIX LuaContextRegistry single-slot
- verdict: TP (severity is understated by reviewer; should be Error not Info — see multi-reviewer consensus)
- severity: Error (upgraded from reviewer's Info)
- evidence: src/posixMain/.../LuaContext.kt:22-56 — `private var current: Pair<LuaState, LuaContext>?`, single slot, no lock; `LuaContextRegistry.current = state to ctx` strongly retains the active LuaContext.
- trace: Verified directly. `current = state to ctx` strong reference + Cleaner that requires `ctx` to be phantom-reachable = the active engine's Cleaner never fires. Multi-engine overwrites the slot. This is the same bug found by 5 other reviewers (C6/RB#4/Nat#5/Mult#6/Sec#6/L7) — understating as Info misses the AutoCloseable violation.

## F12 — JVM pushValue for TableValue does N×3 JNI
- verdict: TP
- severity: Info
- evidence: src/jvmMain/.../LuaNative.kt:189-202 — pushValue table branch.
- trace: Real cost on bulk table push; no quick fix without exposing batched JNI helper.

## F13 — POSIX readValue LUA_TFUNCTION ref=false upvalue loop
- verdict: TP
- severity: WeakWarning
- evidence: src/posixMain/.../LuaValueReader.kt:42-66 — `LUA_TFUNCTION` branch with probe loop.
- trace: 2N+1 C calls per Lua function ref `toValue()`. Cache on FunctionRef construction.

## VERDICT COUNTS
- TP: 13
- FP: 0
- NeedMoreData: 0
- By severity (TP only): Error 1 (F11 uprated), Warning 5 (F1-F4, F6), WeakWarning 4 (F5, F7, F9, F13), Info 3 (F8, F10, F12)

## TOP-RISK TP
- F11 (Error): `LuaContextRegistry` single-slot strongly retains `LuaContext`, suppressing the active engine's Cleaner — confirmed by 6 other reviewers as a real `AutoCloseable` violation. Reviewer's severity of Info wildly understates; promoting to Error.
